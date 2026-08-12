package com.screenrec.pro.core.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Size
import com.screenrec.pro.core.encoder.CodecCapabilityScanner
import com.screenrec.pro.core.encoder.EncoderInfo
import com.screenrec.pro.settings.VideoCodecType

data class DisplayCapabilities(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val supportedRefreshRates: List<Float>,
    val currentRefreshRate: Float,
    val hdrCapable: Boolean
)

data class CodecReport(
    val codecType: VideoCodecType,
    val available: Boolean,
    val encoders: List<EncoderInfo>,
    val maxResolution: Size?
)

data class DeviceDiagnosticsReport(
    val display: DisplayCapabilities,
    val codecReports: List<CodecReport>,
    val androidVersion: Int,
    val soc: String
)

object DeviceDiagnostics {

    // Build.SOC_MODEL só existe a partir da API 31; abaixo disso não há forma
    // pública de obter o nome exato do SoC, então caímos para Build.HARDWARE.
    private fun socModel(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.os.Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: android.os.Build.HARDWARE
        } else {
            android.os.Build.HARDWARE
        }
    }

    fun run(context: Context): DeviceDiagnosticsReport {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val refreshRates = display.supportedModes.map { it.refreshRate }.distinct().sorted()
        val hdrCapable = try {
            display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }

        val displayCaps = DisplayCapabilities(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            supportedRefreshRates = refreshRates,
            currentRefreshRate = display.refreshRate,
            hdrCapable = hdrCapable
        )

        val codecReports = VideoCodecType.values().map { type ->
            val encoders = CodecCapabilityScanner.findEncodersFor(type.mimeType)
            val infos = encoders.mapNotNull {
                CodecCapabilityScanner.describeEncoder(it, type.mimeType, metrics.widthPixels, metrics.heightPixels)
            }
            CodecReport(
                codecType = type,
                available = encoders.isNotEmpty(),
                encoders = infos,
                maxResolution = CodecCapabilityScanner.maxSupportedResolution(type.mimeType)
            )
        }

        return DeviceDiagnosticsReport(
            display = displayCaps,
            codecReports = codecReports,
            androidVersion = android.os.Build.VERSION.SDK_INT,
            soc = socModel()
        )
    }
}
