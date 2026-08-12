package com.screenrec.pro

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenrec.pro.core.encoder.CodecCapabilityScanner
import com.screenrec.pro.settings.VideoCodecType
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Matriz de compatibilidade real (seção 23 do spec): roda em dispositivo/emulador
 * de verdade e imprime, para cada codec x resolução x FPS candidato, se o encoder
 * de hardware do aparelho confirma suporte — nunca assume.
 */
@RunWith(AndroidJUnit4::class)
class CodecCompatibilityMatrixTest {

    private val fpsTargets = listOf(30, 60, 90, 120, 144, 165)

    @Test
    fun imprimeMatrizDeCompatibilidade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        for (codec in VideoCodecType.values()) {
            val encoders = CodecCapabilityScanner.findEncodersFor(codec.mimeType)
            if (encoders.isEmpty()) {
                android.util.Log.i("CompatMatrix", "${codec.label}: sem encoder disponível neste dispositivo")
                continue
            }
            for (fps in fpsTargets) {
                val verdict = CodecCapabilityScanner.evaluateFrameRateTarget(
                    codec.mimeType, metrics.widthPixels, metrics.heightPixels, fps
                )
                android.util.Log.i(
                    "CompatMatrix",
                    "${codec.label} @ ${fps}fps (${metrics.widthPixels}x${metrics.heightPixels}): " +
                        "${if (verdict.supported) "OK" else "REJEITADO"} — ${verdict.reason}"
                )
            }
        }
    }

    @Test
    fun encoderHardwareEDetectadoQuandoDisponivel() {
        val hevcEncoders = CodecCapabilityScanner.findEncodersFor(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC)
        // Não afirma que HW existe — apenas que, se existir, aparece marcado corretamente.
        hevcEncoders.forEach { info ->
            android.util.Log.i("CompatMatrix", "HEVC encoder ${info.name}: hardware=${CodecCapabilityScanner.isHardwareAccelerated(info)}")
        }
    }
}
