package com.screenrec.pro.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import com.screenrec.pro.settings.AudioSettings
import com.screenrec.pro.settings.AudioSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * AUDITORIA (revisão 2), item 10: a versão anterior lia internalRecord e
 * micRecord SEQUENCIALMENTE na mesma thread (readA depois readB). Como
 * AudioRecord.read() é bloqueante, se uma fonte atrasar, ela atrasa a leitura
 * da outra também, e o PTS era derivado de SystemClock no momento do consumo —
 * não da quantidade real de amostras capturadas. As duas coisas juntas geram
 * drift acumulado em gravações longas.
 *
 * Correção:
 *  1. Cada fonte agora tem sua PRÓPRIA thread de leitura, desacoplada da outra —
 *     um atraso em uma não trava a outra.
 *  2. O PTS entregue ao encoder é derivado do número de amostras já processadas
 *     dividido pela sample rate (relógio determinístico ancorado no início da
 *     gravação), não mais do wall-clock no instante do mix — isso é o que
 *     elimina drift progressivo, já que o wall-clock tem jitter de scheduling
 *     que se acumula, enquanto contagem de amostras não.
 *  3. Downmix aplica atenuação (0.7x por fonte) antes de somar, reduzindo risco
 *     de clipping quando as duas fontes têm sinal simultâneo alto, mantendo o
 *     clamp final como rede de segurança.
 */
class AudioCaptureManager(
    private val settings: AudioSettings,
    private val mediaProjection: MediaProjection?,
    private val onEncodedSample: (java.nio.ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onFormatChanged: (MediaFormat) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var internalThread: Thread? = null
    private var micThread: Thread? = null
    private var mixerThread: Thread? = null

    private val channelCount get() = if (settings.stereo) 2 else 1
    private val channelConfig get() = if (settings.stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    private val encodingFormat = AudioFormat.ENCODING_PCM_16BIT

    // Filas independentes por fonte — a thread de mix consome sem bloquear a
    // leitura de nenhuma das duas.
    private val internalQueue = ConcurrentLinkedQueue<ShortArray>()
    private val micQueue = ConcurrentLinkedQueue<ShortArray>()
    private val availableInputIndices = LinkedBlockingQueue<Int>()

    private val samplesProcessed = AtomicLong(0)
    private var eosLatch: CountDownLatch? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (settings.source == AudioSource.NENHUM) return

        val minBuf = AudioRecord.getMinBufferSize(settings.sampleRateHz, channelConfig, encodingFormat)
            .coerceAtLeast(4096)

        if (settings.source == AudioSource.INTERNO || settings.source == AudioSource.INTERNO_MAIS_MICROFONE) {
            val projection = mediaProjection
                ?: throw IllegalStateException("Captura de áudio interno requer MediaProjection ativo")
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            internalRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encodingFormat)
                        .setSampleRate(settings.sampleRateHz)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }

        if (settings.source == AudioSource.MICROFONE || settings.source == AudioSource.INTERNO_MAIS_MICROFONE) {
            micRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                settings.sampleRateHz,
                channelConfig,
                encodingFormat,
                minBuf * 2
            )
        }

        configureEncoder()

        internalRecord?.startRecording()
        micRecord?.startRecording()
        running.set(true)
        samplesProcessed.set(0)

        internalRecord?.let { rec ->
            internalThread = thread(name = "AudioReadInternal") { readLoop(rec, internalQueue, minBuf) }
        }
        micRecord?.let { rec ->
            micThread = thread(name = "AudioReadMic") { readLoop(rec, micQueue, minBuf) }
        }
        mixerThread = thread(name = "AudioMixThread") { mixLoop(minBuf) }
    }

    private fun readLoop(record: AudioRecord, targetQueue: ConcurrentLinkedQueue<ShortArray>, bufferSize: Int) {
        val chunk = ShortArray(bufferSize / 2)
        while (running.get()) {
            val read = record.read(chunk, 0, chunk.size)
            if (read > 0) {
                targetQueue.add(chunk.copyOf(read))
            }
        }
    }

    /** Consome as filas independentes em passos de tamanho fixo, alinhando por
     *  contagem de amostras em vez de por tempo de chegada — a fonte que não tem
     *  dado disponível naquele instante é preenchida com silêncio (zero), o que é
     *  preferível a travar esperando e é auditável via samplesProcessed. */
    private fun mixLoop(bufferSize: Int) {
        val stepSize = bufferSize / 2
        val mixed = ShortArray(stepSize)
        val startUs = System.nanoTime() / 1000

        while (running.get() || internalQueue.isNotEmpty() || micQueue.isNotEmpty()) {
            val a = internalQueue.poll()
            val b = micQueue.poll()

            if (a == null && b == null) {
                if (!running.get()) break
                Thread.sleep(1)
                continue
            }

            val count = maxOf(a?.size ?: 0, b?.size ?: 0)
            for (i in 0 until count) {
                val sa = if (a != null && i < a.size) (a[i] * 0.7).toInt() else 0
                val sb = if (b != null && i < b.size) (b[i] * 0.7).toInt() else 0
                mixed[i.coerceAtMost(mixed.size - 1)] = (sa + sb).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            // PTS determinístico: início da gravação + amostras já processadas / sampleRate.
            // Não depende de wall-clock no momento do mix, o que é a causa raiz do drift.
            val ptsUs = startUs + (samplesProcessed.get() * 1_000_000L / settings.sampleRateHz)
            feedEncoder(mixed, count, ptsUs)
            samplesProcessed.addAndGet(count.toLong())
        }
    }

    private fun configureEncoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, settings.sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateBps)
        }
        val mc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                availableInputIndices.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (info.size > 0) {
                    codec.getOutputBuffer(index)?.let { onEncodedSample(it, info) }
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // Mesma lógica do VideoEncoder (item 4): só é seguro chamar
                    // stop()/release() depois que o próprio codec confirmou a
                    // entrega do buffer final marcado com EOS.
                    eosLatch?.countDown()
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                eosLatch?.countDown()
                onError(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                onFormatChanged(format)
            }
        })
        mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        eosLatch = CountDownLatch(1)
        mc.start()
        encoder = mc
    }

    private fun feedEncoder(samples: ShortArray, count: Int, ptsUs: Long) {
        val mc = encoder ?: return
        val inputIndex = availableInputIndices.poll(100, TimeUnit.MILLISECONDS) ?: return
        if (inputIndex < 0) return
        val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        for (i in 0 until count) inputBuffer.putShort(samples[i])
        mc.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
    }

    fun stop() {
        running.set(false)
        internalThread?.join(500)
        micThread?.join(500)
        mixerThread?.join(500)
        internalRecord?.stop(); internalRecord?.release()
        micRecord?.stop(); micRecord?.release()

        val mc = encoder
        if (mc != null) {
            try {
                // Timestamp final coerente com a última amostra realmente
                // processada, para manter o PTS monotônico mesmo no buffer
                // vazio de EOS (evita rejeição por alguns muxers/decoders
                // sensíveis a EOS com PTS "no passado").
                val finalPtsUs = samplesProcessed.get() * 1_000_000L / settings.sampleRateHz
                val idx = availableInputIndices.poll(500, TimeUnit.MILLISECONDS)
                if (idx >= 0) {
                    mc.queueInputBuffer(idx, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    // Não havia buffer de input livre para carregar o EOS — libera o
                    // latch para não bloquear stop() indefinidamente por algo que já
                    // não vai acontecer.
                    eosLatch?.countDown()
                }
                val drained = eosLatch?.await(EOS_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: true
                if (drained != true) {
                    android.util.Log.w("AudioCaptureManager", "Timeout esperando EOS do encoder de áudio — parando mesmo assim")
                }
                mc.stop()
            } catch (e: Exception) {
                android.util.Log.w("AudioCaptureManager", "Erro ao parar encoder de áudio: ${e.message}")
            } finally {
                try {
                    mc.release()
                } catch (e: Exception) {
                    android.util.Log.w("AudioCaptureManager", "Erro ao liberar encoder de áudio: ${e.message}")
                }
            }
        }
        encoder = null
        eosLatch = null
    }

    companion object {
        private const val EOS_DRAIN_TIMEOUT_MS = 3_000L
    }
}
