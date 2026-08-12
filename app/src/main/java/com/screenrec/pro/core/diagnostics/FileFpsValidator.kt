package com.screenrec.pro.core.diagnostics

import android.media.MediaExtractor
import android.media.MediaFormat

data class FileFpsReport(
    val frameCount: Int,
    val durationUs: Long,
    val averageFps: Double,
    val minIntervalMs: Double,
    val maxIntervalMs: Double,
    val targetFps: Int,
    val confirmed: Boolean,
    val verdict: String
)

/**
 * Item 5 da auditoria: a única forma honesta de afirmar "165 FPS real" é reler o
 * arquivo .mp4 já fechado e contar amostras de vídeo + seus presentationTimeUs
 * reais via MediaExtractor — isso é o que o player vai efetivamente reproduzir,
 * e não depende de nenhuma suposição sobre o pipeline de captura.
 *
 * Critério de confirmação: FPS médio do arquivo >= 97% do target E nenhum gap
 * entre frames consecutivos maior que 3x o intervalo esperado (o que indicaria
 * um stall grave mascarado por uma média aceitável).
 */
object FileFpsValidator {

    fun validate(filePath: String, targetFps: Int): FileFpsReport {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
        } catch (e: Exception) {
            return FileFpsReport(0, 0, 0.0, 0.0, 0.0, targetFps, false, "Não foi possível abrir o arquivo: ${e.message}")
        }

        var videoTrack = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrack = i
                break
            }
        }

        if (videoTrack < 0) {
            extractor.release()
            return FileFpsReport(0, 0, 0.0, 0.0, 0.0, targetFps, false, "Nenhuma trilha de vídeo encontrada no arquivo")
        }

        extractor.selectTrack(videoTrack)

        val timestampsUs = mutableListOf<Long>()
        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) break
            timestampsUs.add(sampleTime)
            if (!extractor.advance()) break
        }
        extractor.release()

        if (timestampsUs.size < 2) {
            return FileFpsReport(timestampsUs.size, 0, 0.0, 0.0, 0.0, targetFps, false, "Amostras insuficientes no arquivo para calcular FPS")
        }

        // MediaExtractor não garante ordem de apresentação ao percorrer, então ordenamos.
        timestampsUs.sort()
        val durationUs = timestampsUs.last() - timestampsUs.first()
        val frameCount = timestampsUs.size
        val averageFps = if (durationUs > 0) (frameCount - 1) * 1_000_000.0 / durationUs else 0.0

        val intervalsMs = timestampsUs.zipWithNext { a, b -> (b - a) / 1000.0 }
        val minInterval = intervalsMs.minOrNull() ?: 0.0
        val maxInterval = intervalsMs.maxOrNull() ?: 0.0
        val expectedIntervalMs = 1000.0 / targetFps

        val fpsOk = averageFps >= targetFps * 0.97
        val noSevereStall = maxInterval <= expectedIntervalMs * 3.0
        val confirmed = fpsOk && noSevereStall

        val verdict = when {
            confirmed -> "$targetFps FPS CONFIRMADO — arquivo final com média de ${"%.1f".format(averageFps)} FPS"
            !fpsOk -> "$targetFps FPS NÃO CONFIRMADO — arquivo final tem média real de ${"%.1f".format(averageFps)} FPS"
            else -> "$targetFps FPS NÃO CONFIRMADO — média aceitável (${"%.1f".format(averageFps)} FPS) mas houve stall de ${"%.1f".format(maxInterval)}ms entre frames"
        }

        return FileFpsReport(frameCount, durationUs, averageFps, minInterval, maxInterval, targetFps, confirmed, verdict)
    }
}
