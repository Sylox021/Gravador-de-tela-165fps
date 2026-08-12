package com.screenrec.pro.core.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface

/**
 * Conecta o MediaProjection concedido pelo usuário a uma VirtualDisplay que
 * desenha diretamente na Surface de entrada do MediaCodec. Nenhuma etapa de
 * CPU/Bitmap é inserida aqui — a composição é feita pelo SurfaceFlinger.
 *
 * AUDITORIA (revisão final), item 6: a versão anterior chamava
 * `mediaProjection.stop()` dentro do próprio [stop] (encerramento pedido pelo
 * usuário/serviço) SEM antes desregistrar o callback — como `stop()` dispara
 * `Callback.onStop()` de forma síncrona/imediata em vários dispositivos, isso
 * causava uma reentrância: [onProjectionStopped] (que está ligado a
 * `RecordingService.stopRecording()`) era chamado de novo NO MEIO da primeira
 * execução de `stopRecording()`, resultando em stop() duplicado de encoder,
 * áudio e muxer (o segundo `muxer.release()` lança IllegalStateException, e o
 * segundo `stopSelf()`/`stopForeground()` é redundante). Correção: o callback
 * é desregistrado antes de chamar `mediaProjection.stop()` quando o
 * encerramento é iniciado por nós mesmos, então [onProjectionStopped] só
 * dispara quando o sistema/usuário revoga a projeção por fora do nosso
 * próprio fluxo de parada (o caso que ele realmente existe para tratar).
 * AUDITORIA (revisão final), item 12 (consequência encontrada ao revisar o
 * benchmark): [BenchmarkRunner] cria um [ScreenCaptureManager] NOVO para cada
 * linha da matriz (H.264/HEVC/AV1 x variações de bitrate/resolução), mas
 * reutiliza a MESMA instância de [mediaProjection] em todas elas — um único
 * consentimento de captura de tela concedido pelo usuário, testado várias
 * vezes em sequência. `MediaProjection.stop()` invalida esse token
 * permanentemente; se [stop] sempre o chamasse, a primeira linha do benchmark
 * mataria a projeção e TODAS as linhas seguintes falhariam silenciosamente ao
 * tentar criar uma nova VirtualDisplay. Por isso [stop] agora recebe
 * [stopProjection] (default `true`, o comportamento existente e correto para
 * RecordingService, onde parar significa mesmo encerrar a sessão de captura).
 * BenchmarkRunner passa `false` entre linhas e só o chamador externo
 * (MainActivity.runFullBenchmark) encerra a projeção de fato, uma única vez,
 * depois que a matriz inteira terminou.
 */
class ScreenCaptureManager(
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: () -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    init {
        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
    }

    fun start(
        encoderInputSurface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenRecorderPro-Capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderInputSurface,
            null,
            null
        )
    }

    /** Usado quando resolução/orientação muda em tempo real (ex: rotação de tela). */
    fun resize(width: Int, height: Int, densityDpi: Int) {
        virtualDisplay?.resize(width, height, densityDpi)
    }

    /**
     * @param stopProjection Se `true` (padrão, usado pela gravação real), também
     *   encerra o [MediaProjection] — a sessão de captura acaba de vez. Se
     *   `false` (usado pelo BenchmarkRunner entre linhas da matriz), libera só a
     *   VirtualDisplay desta instância e mantém a projeção viva para a próxima
     *   linha reutilizar o mesmo consentimento do usuário.
     */
    fun stop(stopProjection: Boolean = true) {
        virtualDisplay?.release()
        virtualDisplay = null
        if (!stopProjection) return
        // Desregistra ANTES de parar: encerramento iniciado por nós não deve
        // reentrar em onProjectionStopped (ver nota da classe, item 6).
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            android.util.Log.w("ScreenCaptureManager", "Erro ao desregistrar callback de projeção: ${e.message}")
        }
        mediaProjection.stop()
    }

    companion object {
        fun screenDensityDpi(metrics: DisplayMetrics): Int = metrics.densityDpi
    }
}
