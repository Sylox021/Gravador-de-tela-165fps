package com.screenrec.pro.core.performance

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * AUDITORIA (revisão 2): a versão anterior deste arquivo media "capture FPS" a
 * partir de chamadas a onFrameAvailableFromSurface(), que na prática nunca eram
 * disparadas por um callback real do VirtualDisplay/SurfaceFlinger — as APIs
 * públicas do Android NÃO expõem um retorno por frame de "a composição desenhou
 * agora". Reportar aquele número como FPS de captura era, na prática, inventar
 * uma métrica. Isso foi removido.
 *
 * A partir desta revisão, o que é medido e como:
 *
 *  - ENCODER OUTPUT FPS (real, alta confiança): calculado a partir dos
 *    `presentationTimeUs` dos buffers de saída do MediaCodec — o único
 *    timestamp que o sistema garante corresponder a um frame efetivamente
 *    codificado. Fonte: VideoEncoder.onOutputBufferAvailable -> BufferInfo.
 *
 *  - ENCODER THROUGHPUT (real, alta confiança): frames de saída por segundo de
 *    wall-clock, útil para detectar engasgos mesmo quando os PTS internos
 *    parecem corretos (ex: encoder atrasado processando em rajada).
 *
 *  - CAPTURE FPS (estimativa, confiança baixa/indisponível): não existe fonte
 *    pública para isso. O campo `captureFpsEstimate` é preenchido apenas se
 *    `onEncoderInputPulse()` for chamado (proxy indireto via disponibilidade de
 *    input do encoder), e vem marcado com `captureFpsIsReliable = false` para
 *    que a UI nunca o apresente como "captura real".
 *
 *  - FINAL FILE FPS (real, altíssima confiança, mas só disponível após o
 *    arquivo ser fechado): ver FileFpsValidator, que lê o arquivo .mp4 final
 *    com MediaExtractor e calcula FPS médio a partir da contagem de amostras e
 *    duração real do arquivo — a prova definitiva pedida no item 5 da auditoria.
 */
data class FrameSnapshot(
    val targetFps: Int,
    val encoderOutputFps: Double,       // real, via PTS dos samples codificados
    val encoderThroughputFps: Double,   // real, via wall-clock dos callbacks de output
    val captureFpsEstimate: Double,     // estimativa indireta — NÃO confiável isoladamente
    val captureFpsIsReliable: Boolean,  // sempre false nas APIs públicas atuais; ver doc acima
    val droppedFrames: Long,
    val duplicatedFrames: Long,
    val delayedFrames: Long,
    val jitterMs: Double,               // jitter calculado sobre os PTS reais do encoder
    val elapsedMs: Long
)

class FrameMetricsEngine(private val targetFps: Int) {

    private val encoderPtsUs = ArrayDeque<Long>()
    private val encoderCallbackWallNs = ArrayDeque<Long>()
    private val inputPulseWallNs = ArrayDeque<Long>()

    private var droppedFrames = 0L
    private var duplicatedFrames = 0L
    private var delayedFrames = 0L
    private var recordingStartNs = 0L
    private var lastPtsUs: Long? = null
    private var expectedIntervalUs = 1_000_000L / targetFps.coerceAtLeast(1)

    private val windowNs = 2_000_000_000L

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<FrameSnapshot> = _snapshot

    fun start() {
        recordingStartNs = SystemClock.elapsedRealtimeNanos()
        encoderPtsUs.clear()
        encoderCallbackWallNs.clear()
        inputPulseWallNs.clear()
        droppedFrames = 0
        duplicatedFrames = 0
        delayedFrames = 0
        lastPtsUs = null
    }

    /**
     * Única fonte de verdade para FPS de codificação. Chamado pelo VideoEncoder a
     * cada onOutputBufferAvailable com o presentationTimeUs real do BufferInfo.
     */
    @Synchronized
    fun onEncodedSamplePts(presentationTimeUs: Long) {
        val wallNs = SystemClock.elapsedRealtimeNanos()

        lastPtsUs?.let { prev ->
            val deltaUs = presentationTimeUs - prev
            if (deltaUs <= 0) {
                duplicatedFrames += 1
            } else if (deltaUs > expectedIntervalUs * 1.8) {
                droppedFrames += (deltaUs / expectedIntervalUs) - 1
            } else if (deltaUs < expectedIntervalUs * 0.2) {
                duplicatedFrames += 1
            }
        }
        lastPtsUs = presentationTimeUs

        encoderPtsUs.addLast(presentationTimeUs)
        trimWindowUs(encoderPtsUs, presentationTimeUs)

        encoderCallbackWallNs.addLast(wallNs)
        trimWindowNs(encoderCallbackWallNs, wallNs)

        publish()
    }

    /**
     * Proxy OPCIONAL e explicitamente não confiável — nunca usado para decidir
     * "165 FPS confirmado", apenas exibido na UI com a etiqueta correspondente.
     */
    @Synchronized
    fun onEncoderInputPulse() {
        val wallNs = SystemClock.elapsedRealtimeNanos()
        inputPulseWallNs.addLast(wallNs)
        trimWindowNs(inputPulseWallNs, wallNs)
        publish()
    }

    fun markDelayedFrame() {
        delayedFrames += 1
    }

    private fun trimWindowUs(deque: ArrayDeque<Long>, nowUs: Long) {
        val windowUs = windowNs / 1000
        while (deque.isNotEmpty() && nowUs - deque.first() > windowUs) deque.removeFirst()
    }

    private fun trimWindowNs(deque: ArrayDeque<Long>, nowNs: Long) {
        while (deque.isNotEmpty() && nowNs - deque.first() > windowNs) deque.removeFirst()
    }

    private fun fpsFromPtsWindowUs(deque: ArrayDeque<Long>): Double {
        if (deque.size < 2) return 0.0
        val spanUs = deque.last() - deque.first()
        if (spanUs <= 0) return 0.0
        return (deque.size - 1) * 1_000_000.0 / spanUs
    }

    private fun fpsFromWallWindow(deque: ArrayDeque<Long>): Double {
        if (deque.size < 2) return 0.0
        val spanNs = deque.last() - deque.first()
        if (spanNs <= 0) return 0.0
        return (deque.size - 1) * 1_000_000_000.0 / spanNs
    }

    private fun jitterMsFromPts(deque: ArrayDeque<Long>): Double {
        if (deque.size < 3) return 0.0
        val intervals = deque.zipWithNext { a, b -> (b - a) / 1000.0 }
        val mean = intervals.average()
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        return sqrt(variance)
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtimeNanos()
        _snapshot.value = FrameSnapshot(
            targetFps = targetFps,
            encoderOutputFps = fpsFromPtsWindowUs(encoderPtsUs),
            encoderThroughputFps = fpsFromWallWindow(encoderCallbackWallNs),
            captureFpsEstimate = fpsFromWallWindow(inputPulseWallNs),
            captureFpsIsReliable = false,
            droppedFrames = droppedFrames,
            duplicatedFrames = duplicatedFrames,
            delayedFrames = delayedFrames,
            jitterMs = jitterMsFromPts(encoderPtsUs),
            elapsedMs = if (recordingStartNs == 0L) 0 else (now - recordingStartNs) / 1_000_000
        )
    }

    private fun emptySnapshot() = FrameSnapshot(targetFps, 0.0, 0.0, 0.0, false, 0, 0, 0, 0.0, 0)
}
