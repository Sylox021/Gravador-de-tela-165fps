package com.screenrec.pro.core.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.screenrec.pro.core.performance.FrameMetricsEngine
import com.screenrec.pro.settings.VideoSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EncoderConfigException(message: String) : Exception(message)

/**
 * Encapsula um MediaCodec em modo Surface-input + async callback.
 * O encoder puxa frames diretamente da Surface produzida pelo VirtualDisplay —
 * não há Bitmap, não há cópia CPU, não há downscale manual.
 *
 * AUDITORIA (revisão 2):
 *  - Item 12: o encoder a usar agora é decidido FORA desta classe, por
 *    EncoderSelector, e passado via [encoderName]. Esta classe não escolhe mais
 *    "o primeiro" — apenas aplica a escolha já pontuada pelas capacidades reais.
 *  - Item 4: onOutputBufferAvailable agora alimenta FrameMetricsEngine com o
 *    presentationTimeUs REAL do BufferInfo (metrics.onEncodedSamplePts), não
 *    mais com o timestamp de wall-clock do callback onInputBufferAvailable.
 *    Esse callback de input continua existindo, mas agora só alimenta o proxy
 *    explicitamente marcado como não confiável (onEncoderInputPulse).
 *  - Item 11: HDR não é mais decidido internamente checando só o encoder — o
 *    chamador passa [hdrDecision] já resolvido por HdrChainValidator.
 *  - Item 4 (revisão final): a versão anterior de [stop] chamava
 *    signalEndOfInputStream() e, na sequência, codec.stop() — em modo async
 *    isso é uma corrida: o MediaCodec pode não ter processado/entregue ainda o
 *    buffer de saída marcado com BUFFER_FLAG_END_OF_STREAM (nem os últimos
 *    frames em voo) quando stop() é chamado, cortando o final da gravação e,
 *    em alguns dispositivos, derrubando uma exceção porque stop() foi chamado
 *    enquanto ainda havia trabalho pendente no codec. Agora [stop] bloqueia
 *    (com timeout) até o callback confirmar recebimento do buffer de EOS via
 *    [eosLatch], garantindo que TODO frame gerado antes do sinal de fim seja
 *    de fato entregue ao muxer antes de o codec ser liberado.
 */
class VideoEncoder(
    private val settings: VideoSettings,
    private val encoderName: String,
    private val resolvedWidth: Int,
    private val resolvedHeight: Int,
    private val resolvedBitrateBps: Int,
    private val enableHdr: Boolean,
    private val metrics: FrameMetricsEngine,
    private val onEncodedSample: (buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) -> Unit,
    private val onFormatChanged: (MediaFormat) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var eosLatch: CountDownLatch? = null
    @Volatile private var eosSignaled = false

    fun configureAndStart(): Surface {
        val format = MediaFormat.createVideoFormat(settings.codec.mimeType, resolvedWidth, resolvedHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, resolvedBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, effectiveTargetFps())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            if (enableHdr) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            }
        }

        val mc = MediaCodec.createByCodecName(encoderName)
        mc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Modo Surface: MediaCodec consome direto da Surface, este callback
                // não é usado para enfileirar dado manualmente. Serve apenas como
                // proxy indireto e EXPLICITAMENTE NÃO CONFIÁVEL de ritmo de produção
                // (ver documentação em FrameMetricsEngine.onEncoderInputPulse).
                metrics.onEncoderInputPulse()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0) {
                        // Fonte real de verdade: presentationTimeUs do BufferInfo,
                        // gerado pelo próprio MediaCodec para o frame efetivamente
                        // codificado — não é um timestamp inventado pelo app.
                        metrics.onEncodedSamplePts(info.presentationTimeUs)
                        val buffer = codec.getOutputBuffer(index) ?: return
                        onEncodedSample(buffer, info)
                    }
                } catch (e: Exception) {
                    onError(e)
                } finally {
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // Confirma que o próprio MediaCodec entregou o marcador de fim
                        // de stream — só a partir daqui é seguro chamar stop()/release()
                        // sem perder amostras em voo.
                        eosLatch?.countDown()
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                // Se o codec errar, não há mais como receber o buffer de EOS —
                // libera o latch para que stop() não fique bloqueado até o timeout.
                eosLatch?.countDown()
                onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                onFormatChanged(format)
            }
        })

        try {
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            mc.release()
            throw EncoderConfigException(
                "Encoder $encoderName rejeitou a configuração ($resolvedWidth x $resolvedHeight @ " +
                    "${effectiveTargetFps()}fps, ${resolvedBitrateBps / 1_000_000}Mbps): ${e.message}"
            )
        }

        val surface = mc.createInputSurface()
        eosLatch = CountDownLatch(1)
        eosSignaled = false
        mc.start()

        codec = mc
        inputSurface = surface
        return surface
    }

    /**
     * Encerra a captura, sinaliza EOS e SÓ chama stop()/release() depois de
     * confirmar (via [eosLatch]) que o último buffer de saída — marcado com
     * BUFFER_FLAG_END_OF_STREAM — já foi entregue a [onEncodedSample]. Um
     * timeout de segurança evita travar indefinidamente caso o codec nunca
     * entregue o marcador (dispositivo com firmware defeituoso).
     */
    fun stop() {
        val mc = codec
        if (mc == null) {
            inputSurface?.release()
            inputSurface = null
            return
        }
        try {
            if (!eosSignaled) {
                eosSignaled = true
                mc.signalEndOfInputStream()
            }
            val drained = eosLatch?.await(EOS_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: true
            if (drained != true) {
                Log.w("VideoEncoder", "Timeout esperando EOS do encoder de vídeo — parando mesmo assim, possível perda dos últimos frames")
            }
            mc.stop()
        } catch (e: Exception) {
            Log.w("VideoEncoder", "Erro ao parar encoder: ${e.message}")
        } finally {
            try {
                mc.release()
            } catch (e: Exception) {
                Log.w("VideoEncoder", "Erro ao liberar encoder: ${e.message}")
            }
            inputSurface?.release()
            codec = null
            inputSurface = null
            eosLatch = null
        }
    }

    private fun effectiveTargetFps(): Int =
        if (settings.frameRate.fps > 0) settings.frameRate.fps else 60

    companion object {
        private const val EOS_DRAIN_TIMEOUT_MS = 3_000L
    }
}
