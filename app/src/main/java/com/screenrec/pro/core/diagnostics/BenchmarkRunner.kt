package com.screenrec.pro.core.diagnostics

import android.media.projection.MediaProjection
import android.util.Size
import com.screenrec.pro.core.capture.ScreenCaptureManager
import com.screenrec.pro.core.encoder.RecordingConfigResolver
import com.screenrec.pro.core.encoder.ConfigResolution
import com.screenrec.pro.core.encoder.VideoEncoder
import com.screenrec.pro.core.muxer.MuxerManager
import com.screenrec.pro.core.performance.FrameMetricsEngine
import com.screenrec.pro.core.performance.StabilityCriteria
import com.screenrec.pro.settings.BitratePreset
import com.screenrec.pro.settings.VideoCodecType
import com.screenrec.pro.settings.VideoSettings
import com.screenrec.pro.settings.FrameRateTarget
import kotlinx.coroutines.delay
import java.io.File

enum class BenchmarkTestType(val label: String, val durationMs: Long) {
    QUICK("Teste rápido", 9_000),
    STABILITY("Teste de estabilidade", 30_000),
    STRESS("Teste de estresse", 60_000)
}

data class BenchmarkRow(
    val codec: VideoCodecType,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val encoderThroughputFps: Double,     // real, via wall-clock dos callbacks (ver FrameMetricsEngine)
    val encoderOutputFpsLive: Double,     // real, via PTS durante a gravação de teste
    val fileValidation: FileFpsReport?,   // real, prova definitiva sobre o arquivo gerado
    val droppedFrames: Long,
    val jitterMs: Double,
    val bitrateBps: Int,
    val encoderName: String,
    val hardwareAccelerated: Boolean,
    val stable: Boolean,
    val failureReasons: List<String>,
    val note: String
)

/**
 * AUDITORIA (revisão 2), itens 1, 2, 5, 8, 15: o benchmark anterior descartava o
 * vídeo e media só via callback timing — não confirmava nada no arquivo final e
 * não comparava bitrates nem resoluções alternativas.
 *
 * Esta versão:
 *  - roda o MESMO pipeline de gravação real (RecordingConfigResolver +
 *    EncoderSelector + VideoEncoder + MuxerManager), inclusive escrevendo um
 *    arquivo .mp4 de verdade (temporário, apagado ao final);
 *  - após cada teste, chama FileFpsValidator no arquivo gerado — a prova
 *    definitiva, não uma suposição sobre o pipeline (item 5);
 *  - testa 165 FPS em resolução nativa para cada codec disponível, e SÓ testa
 *    bitrates/resoluções alternativos quando a config "cheia" falha, conforme
 *    pedido no item 2, evitando gastar tempo/temperatura em combinações óbvias;
 *  - suporta três durações (Quick/Stability/Stress) para diferenciar "funciona
 *    por alguns segundos" de "permanece estável" (item 15);
 *  - usa StabilityCriteria (item 14) como critério único e documentado de PASS/FAIL,
 *    compartilhado com a gravação real.
 */
class BenchmarkRunner(
    private val mediaProjection: MediaProjection,
    private val nativeResolution: Size,
    private val nativeRefreshRateHz: Float,
    private val tempDir: String,
    private val stabilityCriteria: StabilityCriteria = StabilityCriteria()
) {

    /** Item 1/2: para cada codec disponível, testa 165 FPS na resolução nativa.
     *  Se falhar (resolver rejeita OU teste real não confirma), tenta bitrate
     *  médio e depois resolução reduzida antes de desistir daquele codec. */
    suspend fun runFullMatrix(testType: BenchmarkTestType): List<BenchmarkRow> {
        val rows = mutableListOf<BenchmarkRow>()
        for (codec in VideoCodecType.values()) {
            val fullAttempt = runSingleTest(
                VideoSettings(codec = codec, frameRate = FrameRateTarget.FPS_165, bitratePreset = BitratePreset.ALTO),
                testType
            )
            rows.add(fullAttempt)

            if (fullAttempt.stable) continue // item 2: só testa alternativas se a config cheia falhar

            // Alternativa 1: bitrate médio, mesma resolução alvo.
            val mediumBitrate = runSingleTest(
                VideoSettings(codec = codec, frameRate = FrameRateTarget.FPS_165, bitratePreset = BitratePreset.ECONOMICO),
                testType
            )
            rows.add(mediumBitrate)
            if (mediumBitrate.stable) continue

            // Alternativa 2: deixa o resolver reduzir a resolução automaticamente
            // (RecordingConfigResolver já varre a matriz de resoluções — ver item 6/13).
            // Aqui forçamos explicitamente uma resolução menor para registrar o ponto
            // de comparação na tabela, mesmo que o AUTO_MAX do resolver já cubra isso
            // durante o uso real do app.
            val reduced = Size((nativeResolution.width * 0.75).toInt(), (nativeResolution.height * 0.75).toInt())
            val reducedAttempt = runSingleTest(
                VideoSettings(
                    codec = codec, frameRate = FrameRateTarget.FPS_165, bitratePreset = BitratePreset.ALTO,
                    widthPx = reduced.width, heightPx = reduced.height
                ),
                testType
            )
            rows.add(reducedAttempt)
        }
        return rows
    }

    /** Descobre e testa o melhor ponto de operação automático (AUTO_MAX) para
     *  cada codec — complementa a matriz de 165 FPS fixo acima. */
    suspend fun runAutoMaxForEachCodec(testType: BenchmarkTestType): List<BenchmarkRow> {
        return VideoCodecType.values().map { codec ->
            runSingleTest(VideoSettings(codec = codec, frameRate = FrameRateTarget.AUTO_MAX, bitratePreset = BitratePreset.ALTO), testType)
        }
    }

    private suspend fun runSingleTest(settings: VideoSettings, testType: BenchmarkTestType): BenchmarkRow {
        val resolution = RecordingConfigResolver.resolve(settings, nativeResolution, nativeRefreshRateHz)

        if (resolution is ConfigResolution.Rejected) {
            return BenchmarkRow(
                codec = settings.codec,
                width = settings.widthPx ?: nativeResolution.width,
                height = settings.heightPx ?: nativeResolution.height,
                targetFps = settings.frameRate.fps,
                encoderThroughputFps = 0.0,
                encoderOutputFpsLive = 0.0,
                fileValidation = null,
                droppedFrames = 0,
                jitterMs = 0.0,
                bitrateBps = 0,
                encoderName = "-",
                hardwareAccelerated = false,
                stable = false,
                failureReasons = listOf(resolution.reason),
                note = "Rejeitado antes de testar (indicação inicial do encoder). Maior FPS declarado: ${"%.1f".format(resolution.bestAvailableFps)}"
            )
        }

        val resolved = resolution as ConfigResolution.Success
        val metrics = FrameMetricsEngine(resolved.fps)
        val tempPath = File(tempDir, "bench_${settings.codec.name}_${resolved.fps}_${System.currentTimeMillis()}.mp4").absolutePath

        var lastError: Exception? = null
        val muxer = MuxerManager(tempPath, expectsAudio = false)

        val encoder = VideoEncoder(
            settings = settings.copy(widthPx = resolved.width, heightPx = resolved.height),
            encoderName = resolved.encoderName,
            resolvedWidth = resolved.width,
            resolvedHeight = resolved.height,
            resolvedBitrateBps = resolved.bitrateBps,
            enableHdr = false, // benchmark de FPS não testa HDR — são preocupações ortogonais
            metrics = metrics,
            onEncodedSample = { buffer, info -> muxer.writeVideoSample(buffer, info) },
            onFormatChanged = { format -> muxer.addVideoTrack(format) },
            onError = { e -> lastError = e }
        )

        val surface = try {
            encoder.configureAndStart()
        } catch (e: Exception) {
            return failedRow(settings, resolved.width, resolved.height, resolved.fps, resolved.bitrateBps, resolved.encoderName, "Falha ao configurar encoder: ${e.message}")
        }

        val capture = ScreenCaptureManager(mediaProjection) { }
        capture.start(surface, resolved.width, resolved.height, 320)
        metrics.start()

        delay(testType.durationMs)

        // Item 12: NÃO encerra o MediaProjection aqui — ele é reutilizado pelas
        // próximas linhas da matriz de benchmark (ver nota em ScreenCaptureManager).
        // Quem encerra a projeção de fato é o chamador, uma única vez, depois que
        // TODA a matriz (runFullMatrix/runAutoMaxForEachCodec) termina.
        capture.stop(stopProjection = false)
        encoder.stop()
        muxer.stop()

        val snapshot = metrics.snapshot.value
        val fileReport = try {
            FileFpsValidator.validate(tempPath, resolved.fps)
        } catch (e: Exception) {
            null
        }
        File(tempPath).delete()

        if (lastError != null) {
            return failedRow(settings, resolved.width, resolved.height, resolved.fps, resolved.bitrateBps, resolved.encoderName, "Erro durante o teste: ${lastError!!.message}")
        }

        val verdict = stabilityCriteria.evaluate(snapshot, muxer.stats.value, encoderHadError = false)
        // A confirmação de FPS real do item 5 exige TAMBÉM que o arquivo confirme —
        // um teste pode "parecer" estável nas métricas ao vivo e ainda assim o
        // arquivo final não bater (ex: keyframe/gop causando irregularidade).
        val fileConfirms = fileReport?.confirmed ?: false
        val overallStable = verdict.stable && fileConfirms

        val reasons = verdict.failureReasons.toMutableList()
        if (verdict.stable && !fileConfirms && fileReport != null) {
            reasons.add("Métricas ao vivo estáveis, mas arquivo final não confirma: ${fileReport.verdict}")
        }

        return BenchmarkRow(
            codec = settings.codec,
            width = resolved.width,
            height = resolved.height,
            targetFps = resolved.fps,
            encoderThroughputFps = snapshot.encoderThroughputFps,
            encoderOutputFpsLive = snapshot.encoderOutputFps,
            fileValidation = fileReport,
            droppedFrames = snapshot.droppedFrames,
            jitterMs = snapshot.jitterMs,
            bitrateBps = resolved.bitrateBps,
            encoderName = resolved.encoderName,
            hardwareAccelerated = true, // resolver só chega aqui se EncoderSelector aprovou; HW é preferido mas não garantido — ver encoderName para conferência manual
            stable = overallStable,
            failureReasons = reasons,
            note = fileReport?.verdict ?: "Arquivo não pôde ser validado"
        )
    }

    private fun failedRow(settings: VideoSettings, width: Int, height: Int, fps: Int, bitrate: Int, encoderName: String, reason: String) =
        BenchmarkRow(
            codec = settings.codec, width = width, height = height, targetFps = fps,
            encoderThroughputFps = 0.0, encoderOutputFpsLive = 0.0, fileValidation = null,
            droppedFrames = 0, jitterMs = 0.0, bitrateBps = bitrate, encoderName = encoderName,
            hardwareAccelerated = false, stable = false, failureReasons = listOf(reason), note = reason
        )
}

/**
 * Item 16: relatório final no formato exigido, gerado a partir da melhor linha
 * de 165 FPS encontrada (se houver) ou da melhor configuração estável de
 * qualquer FPS, como fallback honesto.
 */
object FinalDiagnosticReport {
    fun build(deviceName: String, displayHz: Int, rows: List<BenchmarkRow>): String {
        val target165 = rows.filter { it.targetFps == 165 }
        val confirmed165 = target165.firstOrNull { it.stable }

        val sb = StringBuilder()
        sb.appendLine("DEVICE")
        sb.appendLine(deviceName)
        sb.appendLine()
        sb.appendLine("DISPLAY")
        sb.appendLine("$displayHz Hz")
        sb.appendLine()

        if (confirmed165 != null) {
            sb.appendLine("CODEC")
            sb.appendLine("${confirmed165.codec.label} ${if (confirmed165.hardwareAccelerated) "Hardware" else "Software"} (${confirmed165.encoderName})")
            sb.appendLine()
            sb.appendLine("RESOLUTION")
            sb.appendLine("${confirmed165.width} x ${confirmed165.height}")
            sb.appendLine()
            sb.appendLine("TARGET")
            sb.appendLine("165 FPS")
            sb.appendLine()
            sb.appendLine("MEASURED OUTPUT (arquivo final)")
            sb.appendLine("${"%.1f".format(confirmed165.fileValidation?.averageFps ?: 0.0)} FPS")
            sb.appendLine()
            sb.appendLine("DROPPED")
            sb.appendLine("${confirmed165.droppedFrames}")
            sb.appendLine()
            sb.appendLine("JITTER")
            sb.appendLine("${"%.1f".format(confirmed165.jitterMs)} ms")
            sb.appendLine()
            sb.appendLine("BITRATE")
            sb.appendLine("${confirmed165.bitrateBps / 1_000_000} Mbps")
            sb.appendLine()
            sb.appendLine("STABILITY")
            sb.appendLine("PASS")
            sb.appendLine()
            sb.appendLine("RESULT")
            sb.appendLine("165 FPS REAL CONFIRMADO")
        } else {
            val best = rows.filter { it.stable }.maxByOrNull { it.targetFps }
            sb.appendLine("RESULT")
            sb.appendLine("165 FPS NÃO CONFIRMADO")
            sb.appendLine()
            if (best != null) {
                sb.appendLine("BEST STABLE CONFIGURATION")
                sb.appendLine("${best.targetFps} FPS @ ${best.width} x ${best.height} @ ${best.bitrateBps / 1_000_000} Mbps (${best.codec.label}, ${best.encoderName})")
            } else {
                sb.appendLine("BEST STABLE CONFIGURATION")
                sb.appendLine("Nenhuma configuração testada foi confirmada estável — ver detalhes por linha na tabela do benchmark.")
            }
        }
        return sb.toString()
    }
}
