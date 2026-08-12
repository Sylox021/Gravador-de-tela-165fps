package com.screenrec.pro

import com.screenrec.pro.core.encoder.RecordingConfigResolver
import com.screenrec.pro.settings.FrameRateTarget
import com.screenrec.pro.settings.VideoCodecType
import com.screenrec.pro.settings.VideoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NOTA: CodecCapabilityScanner depende de android.media.MediaCodecList, que não
 * existe no JVM puro do JUnit local — estes testes de resolução de bitrate/fps
 * precisam rodar como teste instrumentado (androidTest) contra um dispositivo/
 * emulador real, exatamente porque o objetivo do app é nunca inventar números.
 * Ver CodecCompatibilityMatrixTest (androidTest) para essa cobertura.
 *
 * AUDITORIA (revisão final), item 2: a asserção anterior aqui
 * (`megabits != null || megabits == null`) era uma tautologia — sempre
 * verdadeira, não testava nada de fato. Substituída por asserções reais sobre
 * os valores default de VideoSettings, que são puro Kotlin e não dependem de
 * MediaCodecList.
 */
class RecordingConfigResolverTest {

    @Test
    fun `valores default de VideoSettings sao os documentados`() {
        val settings = VideoSettings()
        assertEquals(VideoCodecType.HEVC, settings.codec)
        assertEquals(FrameRateTarget.FPS_60, settings.frameRate)
        assertNull("resolução default deve ser nula (= usa a resolução nativa)", settings.widthPx)
        assertNull(settings.heightPx)
    }

    @Test
    fun `FrameRateTarget FPS_165 mapeia para 165`() {
        assertEquals(165, FrameRateTarget.FPS_165.fps)
    }

    @Test
    fun `AUTO_MAX nao tem fps fixo positivo`() {
        // -1 é o sentinel usado por RecordingConfigResolver.resolve() para saber
        // que deve expandir para a lista [165,144,120,90,60,30] em vez de usar
        // um único valor fixo — ver RecordingConfigResolver.kt.
        assert(FrameRateTarget.AUTO_MAX.fps < 0)
    }
}
