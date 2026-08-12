package com.screenrec.pro.core.encoder

/**
 * Item 12 da auditoria: a versão anterior usava `encoders.firstOrNull()` depois
 * de ordenar só por hardware-acceleration. Isso ignora que um mesmo dispositivo
 * pode ter mais de um encoder de hardware para o mesmo mimeType (comum em SoCs
 * com blocos de vídeo duplicados, ou expondo variantes OMX/C2 do mesmo IP), com
 * capacidades de FPS/resolução/bitrate diferentes entre si.
 *
 * Esta seleção pontua cada encoder disponível pela config REALMENTE solicitada
 * (resolução + fps + bitrate), não por uma heurística genérica.
 */
data class ScoredEncoder(val info: android.media.MediaCodecInfo, val encoderInfo: EncoderInfo, val score: Double, val meetsRequirements: Boolean)

object EncoderSelector {

    fun selectBest(
        mimeType: String,
        width: Int,
        height: Int,
        requestedFps: Int,
        requestedBitrateBps: Int
    ): ScoredEncoder? {
        val candidates = CodecCapabilityScanner.findEncodersFor(mimeType)
        if (candidates.isEmpty()) return null

        val scored = candidates.mapNotNull { codecInfo ->
            val info = CodecCapabilityScanner.describeEncoder(codecInfo, mimeType, width, height) ?: return@mapNotNull null

            val fpsCeiling = info.supportedFrameRatesAtResolution?.upper ?: 0.0
            val meetsFps = fpsCeiling >= requestedFps
            val meetsResolution = width in info.supportedWidths.lower..info.supportedWidths.upper &&
                height in info.supportedHeights.lower..info.supportedHeights.upper
            val meetsBitrate = requestedBitrateBps in info.supportedBitrateRange.lower..info.supportedBitrateRange.upper

            // Pontuação: hardware pesa mais, depois folga de FPS acima do pedido
            // (indica menos risco de instabilidade sob carga), depois folga de bitrate.
            var score = 0.0
            if (info.isHardwareAccelerated) score += 1000.0
            score += (fpsCeiling - requestedFps).coerceAtLeast(0.0) * 2.0
            score += ((info.supportedBitrateRange.upper - requestedBitrateBps).coerceAtLeast(0)) / 1_000_000.0
            if (!meetsFps) score -= 5000.0       // desqualifica na prática, mas mantém no ranking p/ diagnóstico
            if (!meetsResolution) score -= 5000.0
            if (!meetsBitrate) score -= 1000.0    // bitrate é ajustável por clamp, penaliza mas não desqualifica

            ScoredEncoder(codecInfo, info, score, meetsFps && meetsResolution)
        }.sortedByDescending { it.score }

        return scored.firstOrNull { it.meetsRequirements } ?: scored.firstOrNull()
    }
}
