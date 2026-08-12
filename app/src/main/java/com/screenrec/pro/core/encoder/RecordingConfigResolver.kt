package com.screenrec.pro.core.encoder

import android.util.Size
import com.screenrec.pro.settings.BitratePreset
import com.screenrec.pro.settings.FrameRateTarget
import com.screenrec.pro.settings.VideoSettings

sealed class ConfigResolution {
    data class Success(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val encoderName: String,
        val warnings: List<String>
    ) : ConfigResolution()

    data class Rejected(val reason: String, val bestAvailableFps: Double) : ConfigResolution()
}

/**
 * AUDITORIA (revisão 2), itens 6 e 13: a versão anterior só testava a resolução
 * pedida (geralmente a nativa) e, para AUTO_MAX, apenas descia por uma lista fixa
 * de FPS na MESMA resolução. Isso não descobre o "melhor ponto de operação" real
 * quando, por exemplo, o encoder aceita 165fps em 1080p mas não na resolução
 * nativa 2.5K do tablet.
 *
 * Esta versão testa uma MATRIZ resolução x fps (ver `discoverBestOperatingPoint`)
 * e retorna a melhor combinação encontrada, não apenas a primeira que "passa" na
 * resolução original. IMPORTANTE: mesmo aqui, a aprovação por
 * `CodecCapabilityScanner` continua sendo só a "indicação inicial" pedida no
 * item 1 — a confirmação definitiva é responsabilidade do BenchmarkRunner (teste
 * real do pipeline) e do FileFpsValidator (prova no arquivo final), não desta
 * classe. `resolve()` decide o que TENTAR; não decide o que está confirmado.
 */
object RecordingConfigResolver {

    data class OperatingPoint(
        val width: Int,
        val height: Int,
        val fps: Int,
        val encoderName: String,
        val hardwareAccelerated: Boolean
    )

    /** Resoluções candidatas em ordem decrescente de qualidade, derivadas da
     *  resolução nativa (nunca hardcoded para um aparelho específico). */
    private fun resolutionCandidates(native: Size): List<Size> {
        val aspectRatio = native.width.toDouble() / native.height.toDouble()
        val heights = listOf(native.height, 1440, 1080, 720).distinct().filter { it <= native.height }
        return heights.map { h ->
            val w = (h * aspectRatio).toInt().let { it - (it % 2) } // largura par (exigência comum de encoder)
            Size(w, h)
        }.distinct()
    }

    fun resolve(
        settings: VideoSettings,
        nativeResolution: Size,
        nativeRefreshRateHz: Float
    ): ConfigResolution {
        // Config com resolução explícita pedida pelo usuário: não expande a busca,
        // respeita a escolha e só resolve FPS/bitrate para ela.
        if (settings.widthPx != null && settings.heightPx != null) {
            return resolveForResolution(settings, Size(settings.widthPx, settings.heightPx), nativeRefreshRateHz)
        }

        val fpsTargets = if (settings.frameRate == FrameRateTarget.AUTO_MAX) {
            listOf(165, 144, 120, 90, 60, 30)
        } else {
            listOf(settings.frameRate.fps)
        }

        val best = discoverBestOperatingPoint(settings, nativeResolution, fpsTargets)
            ?: return ConfigResolution.Rejected(
                reason = "Nenhuma combinação de resolução e FPS testada é suportada por ${settings.codec.label} neste dispositivo.",
                bestAvailableFps = 0.0
            )

        return resolveForResolution(
            settings.copy(widthPx = best.width, heightPx = best.height),
            Size(best.width, best.height),
            nativeRefreshRateHz,
            forcedFps = best.fps,
            forcedEncoderName = best.encoderName
        )
    }

    /**
     * Item 6/13: varre resolução x fps e retorna o MELHOR ponto (maior fps; em
     * empate, maior resolução) que o encoder confirma suportar. Não para na
     * primeira combinação suportada — continua até esgotar a matriz, porque
     * "165fps em 720p" pode existir mesmo quando "165fps em 1440p" não existe, e
     * o objetivo é encontrar o máximo real, não o primeiro aceitável.
     */
    fun discoverBestOperatingPoint(
        settings: VideoSettings,
        nativeResolution: Size,
        fpsTargets: List<Int>
    ): OperatingPoint? {
        val resolutions = resolutionCandidates(nativeResolution)
        var best: OperatingPoint? = null

        for (fps in fpsTargets) {
            for (res in resolutions) {
                val requestedBitrate = estimateBitrateForScoring(settings, res.width, res.height, fps)
                val scored = EncoderSelector.selectBest(settings.codec.mimeType, res.width, res.height, fps, requestedBitrate)
                if (scored != null && scored.meetsRequirements) {
                    val candidate = OperatingPoint(res.width, res.height, fps, scored.info.name, scored.encoderInfo.isHardwareAccelerated)
                    // Maior FPS testado primeiro (fpsTargets já vem decrescente), então
                    // o primeiro candidato válido encontrado já é o de maior FPS: dentro
                    // desse FPS, resolutions também está em ordem decrescente, então o
                    // primeiro "meetsRequirements" já é a maior resolução para esse FPS.
                    return candidate
                }
            }
        }
        return best
    }

    // Nota: antes havia um cálculo teórico (bits-por-pixel) usado como fallback
    // quando não havia preset/manual definidos. Como BitratePreset.megabits
    // agora é sempre não-nulo (17 valores explícitos, sem opção "auto"), esse
    // cálculo nunca era mais alcançado — removido para não ficar código morto.
    private fun estimateBitrateForScoring(settings: VideoSettings, width: Int, height: Int, fps: Int): Int {
        settings.customBitrateBps?.let { return it }
        return settings.bitratePreset.megabits * 1_000_000
    }

    private fun resolveForResolution(
        settings: VideoSettings,
        resolution: Size,
        nativeRefreshRateHz: Float,
        forcedFps: Int? = null,
        forcedEncoderName: String? = null
    ): ConfigResolution {
        val warnings = mutableListOf<String>()
        val width = resolution.width
        val height = resolution.height

        val chosenFps: Int
        val chosenEncoderName: String
        val encoderInfo: EncoderInfo

        if (forcedFps != null && forcedEncoderName != null) {
            chosenFps = forcedFps
            chosenEncoderName = forcedEncoderName
            val codecInfo = CodecCapabilityScanner.findEncodersFor(settings.codec.mimeType)
                .firstOrNull { it.name == forcedEncoderName }
                ?: return ConfigResolution.Rejected("Encoder $forcedEncoderName não encontrado na segunda checagem", 0.0)
            encoderInfo = CodecCapabilityScanner.describeEncoder(codecInfo, settings.codec.mimeType, width, height)
                ?: return ConfigResolution.Rejected("Encoder $forcedEncoderName não descreve capacidades para $width x $height", 0.0)
        } else {
            val candidateFpsList = if (settings.frameRate == FrameRateTarget.AUTO_MAX) {
                listOf(165, 144, 120, 90, 60, 30)
            } else {
                listOf(settings.frameRate.fps)
            }

            var pickedFps = -1
            var pickedScored: ScoredEncoder? = null
            var bestCeiling = 0.0
            for (candidate in candidateFpsList) {
                val requestedBitrate = estimateBitrateForScoring(settings, width, height, candidate)
                val scored = EncoderSelector.selectBest(settings.codec.mimeType, width, height, candidate, requestedBitrate)
                val ceiling = scored?.encoderInfo?.supportedFrameRatesAtResolution?.upper ?: 0.0
                bestCeiling = maxOf(bestCeiling, ceiling)
                if (scored != null && scored.meetsRequirements) {
                    pickedFps = candidate
                    pickedScored = scored
                    break
                }
            }

            if (pickedFps == -1 || pickedScored == null) {
                return ConfigResolution.Rejected(
                    reason = "Nenhuma taxa solicitada é suportada pelo encoder ${settings.codec.label} em $width x $height.",
                    bestAvailableFps = bestCeiling
                )
            }

            chosenFps = pickedFps
            chosenEncoderName = pickedScored.info.name
            encoderInfo = pickedScored.encoderInfo
        }

        if (chosenFps > nativeRefreshRateHz.toInt()) {
            warnings.add(
                "A tela reporta taxa de atualização de ${nativeRefreshRateHz.toInt()} Hz. " +
                    "Mesmo que o encoder aceite $chosenFps FPS, a composição do sistema pode " +
                    "não entregar frames novos nessa taxa (ver limitação documentada no README)."
            )
        }

        val requestedBitrateBps = estimateBitrateForScoring(settings, width, height, chosenFps)
        val clampedBitrate = encoderInfo.supportedBitrateRange.clamp(requestedBitrateBps)
        if (clampedBitrate != requestedBitrateBps) {
            warnings.add(
                "Bitrate solicitado (${requestedBitrateBps / 1_000_000} Mbps) fora do range do encoder " +
                    "(${encoderInfo.supportedBitrateRange.lower / 1_000_000}-" +
                    "${encoderInfo.supportedBitrateRange.upper / 1_000_000} Mbps). Ajustado para " +
                    "${clampedBitrate / 1_000_000} Mbps."
            )
        }

        if (!encoderInfo.isHardwareAccelerated) {
            warnings.add("Encoder selecionado ($chosenEncoderName) é por SOFTWARE — risco maior de instabilidade e consumo de CPU/bateria.")
        }

        return ConfigResolution.Success(width, height, chosenFps, clampedBitrate, chosenEncoderName, warnings)
    }

    private fun android.util.Range<Int>.clamp(value: Int): Int = value.coerceIn(lower, upper)
}
