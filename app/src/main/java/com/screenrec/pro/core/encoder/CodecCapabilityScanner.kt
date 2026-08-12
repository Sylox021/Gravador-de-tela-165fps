package com.screenrec.pro.core.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size

/**
 * Consulta as capacidades REAIS do dispositivo via android.media.MediaCodecList.
 * Nada aqui é hardcoded — tudo vem da introspecção pública do sistema.
 *
 * Esta classe é a única fonte de verdade sobre "o que é possível", usada tanto
 * pela tela de Diagnóstico quanto pela validação antes de iniciar a gravação.
 */
data class EncoderInfo(
    val codecName: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportedFrameRatesAtResolution: Range<Double>?,
    val supportedBitrateRange: Range<Int>,
    val supportedWidths: Range<Int>,
    val supportedHeights: Range<Int>,
    val maxSupportedInstances: Int,
    val colorFormats: List<Int>,
    val hdrProfilesSupported: List<Int>
)

data class FrameRateVerdict(
    val requestedFps: Int,
    val achievableFps: Double,
    val supported: Boolean,
    val reason: String
)

object CodecCapabilityScanner {

    private val codecList by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    /** Lista todos os encoders de hardware disponíveis para um mimeType, ordenados
     *  colocando os hardware-accelerated primeiro (prioridade exigida pelo spec). */
    fun findEncodersFor(mimeType: String): List<MediaCodecInfo> {
        return codecList.codecInfos
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .sortedByDescending { isHardwareAccelerated(it) }
    }

    /** Heurística oficial recomendada pelo Android: nomes prefixados com "c2.<vendor>."
     *  ou marcados explicitamente via isHardwareAccelerated() (API 29+). */
    fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return try {
            info.isHardwareAccelerated
        } catch (e: NoSuchMethodError) {
            !info.name.startsWith("c2.android.") && !info.name.startsWith("OMX.google.")
        }
    }

    fun describeEncoder(info: MediaCodecInfo, mimeType: String, width: Int, height: Int): EncoderInfo? {
        val caps = info.getCapabilitiesForType(mimeType) ?: return null
        val videoCaps = caps.videoCapabilities ?: return null

        val frameRateRange: Range<Double>? = try {
            videoCaps.getSupportedFrameRatesFor(width, height)
        } catch (e: IllegalArgumentException) {
            // Resolução não suportada por este encoder nesta combinação.
            null
        }

        val hdrProfiles = try {
            caps.profileLevels
                .filter { level ->
                    when (mimeType) {
                        MediaFormat.MIMETYPE_VIDEO_HEVC ->
                            level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                        MediaFormat.MIMETYPE_VIDEO_AV1 ->
                            level.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                        else -> false
                    }
                }
                .map { it.profile }
        } catch (e: Exception) {
            emptyList()
        }

        return EncoderInfo(
            codecName = info.name,
            mimeType = mimeType,
            isHardwareAccelerated = isHardwareAccelerated(info),
            supportedFrameRatesAtResolution = frameRateRange,
            supportedBitrateRange = videoCaps.bitrateRange,
            supportedWidths = videoCaps.supportedWidths,
            supportedHeights = videoCaps.supportedHeights,
            maxSupportedInstances = caps.maxSupportedInstances,
            colorFormats = caps.colorFormats.toList(),
            hdrProfilesSupported = hdrProfiles
        )
    }

    /**
     * Verificação honesta se um FPS alvo é alcançável para a resolução/codec dados.
     * Não faz suposições: se o encoder não expõe faixa de frame rate pra essa
     * resolução, ou o teto reportado é menor que o pedido, retorna supported=false
     * com o maior valor real disponível.
     */
    fun evaluateFrameRateTarget(
        mimeType: String,
        width: Int,
        height: Int,
        requestedFps: Int
    ): FrameRateVerdict {
        val encoders = findEncodersFor(mimeType)
        if (encoders.isEmpty()) {
            return FrameRateVerdict(requestedFps, 0.0, false, "Nenhum encoder disponível para $mimeType")
        }

        var bestCeiling = 0.0
        var bestEncoderName = ""
        for (enc in encoders) {
            val info = describeEncoder(enc, mimeType, width, height) ?: continue
            val ceiling = info.supportedFrameRatesAtResolution?.upper ?: continue
            if (ceiling > bestCeiling) {
                bestCeiling = ceiling
                bestEncoderName = info.codecName
            }
        }

        if (bestCeiling <= 0.0) {
            return FrameRateVerdict(
                requestedFps, 0.0, false,
                "Nenhum encoder reporta suporte a $width x $height nesta configuração"
            )
        }

        return if (bestCeiling >= requestedFps) {
            FrameRateVerdict(requestedFps, bestCeiling, true, "Suportado por $bestEncoderName")
        } else {
            FrameRateVerdict(
                requestedFps, bestCeiling, false,
                "$bestEncoderName reporta teto de ${"%.1f".format(bestCeiling)} FPS para $width x $height"
            )
        }
    }

    /** Maior resolução declarada suportada por qualquer encoder de hardware do mimeType. */
    fun maxSupportedResolution(mimeType: String): Size? {
        val encoders = findEncodersFor(mimeType)
        var best: Size? = null
        var bestArea = 0L
        for (enc in encoders) {
            val caps = enc.getCapabilitiesForType(mimeType)?.videoCapabilities ?: continue
            val w = caps.supportedWidths.upper
            val h = caps.supportedHeights.upper
            val area = w.toLong() * h.toLong()
            if (area > bestArea) {
                bestArea = area
                best = Size(w, h)
            }
        }
        return best
    }
}
