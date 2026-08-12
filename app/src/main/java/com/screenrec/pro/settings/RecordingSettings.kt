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

enum class BitratePreset(val megabits: Int?, val label: String) {
    ECONOMICO(15, "Econômico"),
    ALTO(50, "Alto"),
    MUITO_ALTO(100, "Muito alto"),
    INSANO(250, "Insano / Máxima qualidade"),
    QUASE_LOSSLESS(null, "Lossless ou quase lossless (auto pelo encoder)"),
    PERSONALIZADO(null, "Personalizado")
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
    val bitratePreset: BitratePreset = BitratePreset.ALTO,
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
