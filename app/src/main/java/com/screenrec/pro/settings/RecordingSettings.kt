package com.screenrec.pro.settings

import android.media.MediaFormat

/** Codecs suportados pelo app. Disponibilidade real é verificada em runtime
 *  via [com.screenrec.pro.core.encoder.CodecCapabilityScanner] — nunca presumida. */
enum class VideoCodecType(val mimeType: String, val label: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264 / AVC"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "H.265 / HEVC"),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "AV1")
}

enum class FrameRateTarget(val fps: Int, val label: String) {
    FPS_30(30, "30 FPS"),
    FPS_60(60, "60 FPS"),
    FPS_90(90, "90 FPS"),
    FPS_120(120, "120 FPS"),
    FPS_144(144, "144 FPS"),
    FPS_165(165, "165 FPS"),
    AUTO_MAX(-1, "Automático / Máximo possível")
}

/**
 * Faixa de bitrate ampliada para cobrir gravação em alta resolução e alta taxa
 * de quadros (3K/165 FPS), até 150 Mbps. Os 17 valores abaixo são as opções
 * manuais disponíveis; quatro deles também são expostos como atalhos nomeados
 * (Baixa/Média/Alta/Máxima) na tela principal — mas são estritamente o MESMO
 * valor, não um cálculo separado. 150 Mbps é o teto disponível para permitir a
 * maior qualidade possível; não significa que toda gravação vai de fato usar
 * 150 Mbps — RecordingConfigResolver sempre limita (clamp) ao range real
 * suportado pelo encoder/hardware do aparelho antes de aplicar, e avisa o
 * usuário quando o valor pedido não cabe, em vez de derrubar a gravação.
 */
enum class BitratePreset(val megabits: Int, val label: String) {
    MBPS_2(2, "2 Mbps"),
    MBPS_4(4, "4 Mbps"),
    MBPS_6(6, "6 Mbps"),
    MBPS_8(8, "8 Mbps"),
    MBPS_10(10, "10 Mbps"),
    MBPS_12(12, "12 Mbps"),
    MBPS_16(16, "16 Mbps"),
    MBPS_20(20, "20 Mbps"),
    MBPS_25(25, "25 Mbps"),
    MBPS_30(30, "30 Mbps"),
    MBPS_40(40, "40 Mbps"),
    MBPS_50(50, "50 Mbps"),
    MBPS_60(60, "60 Mbps"),
    MBPS_75(75, "75 Mbps"),
    MBPS_100(100, "100 Mbps"),
    MBPS_125(125, "125 Mbps"),
    MBPS_150(150, "150 Mbps");

    companion object {
        /** Atalhos nomeados — apenas apontam para um valor real da lista acima. */
        val BAIXA = MBPS_8
        val MEDIA = MBPS_30
        val ALTA = MBPS_75
        val MAXIMA = MBPS_150
    }
}

enum class HdrMode { AUTO, FORCE_SDR }

enum class AudioSource { NENHUM, INTERNO, MICROFONE, INTERNO_MAIS_MICROFONE }

data class AudioSettings(
    val source: AudioSource = AudioSource.INTERNO,
    val sampleRateHz: Int = 48_000,
    val bitrateBps: Int = 256_000,
    val stereo: Boolean = true
)

data class VideoSettings(
    val codec: VideoCodecType = VideoCodecType.HEVC,
    val frameRate: FrameRateTarget = FrameRateTarget.FPS_60,
    val bitratePreset: BitratePreset = BitratePreset.MEDIA,
    val customBitrateBps: Int? = null,
    val widthPx: Int? = null,   // null = resolução nativa
    val heightPx: Int? = null,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val keyFrameIntervalSeconds: Int = 2,
    val recordOverlayIntoFile: Boolean = false
)

data class RecordingSettings(
    val video: VideoSettings = VideoSettings(),
    val audio: AudioSettings = AudioSettings(),
    val outputDirectoryUri: String? = null,
    val showLiveOverlay: Boolean = true
)
