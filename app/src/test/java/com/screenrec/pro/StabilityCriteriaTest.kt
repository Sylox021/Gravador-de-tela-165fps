package com.screenrec.pro

import com.screenrec.pro.core.muxer.MuxerStats
import com.screenrec.pro.core.performance.FrameSnapshot
import com.screenrec.pro.core.performance.StabilityCriteria
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDITORIA (revisão final), item 2: StabilityCriteria.evaluate() é lógica pura
 * Kotlin (não toca em nenhuma API do Android) e é o critério único de PASS/FAIL
 * usado tanto pelo BenchmarkRunner quanto pela gravação real — mas não tinha
 * NENHUM teste, real ou tautológico. Esta suíte cobre os seis critérios
 * documentados em StabilityCriteria.kt, individualmente, para que uma futura
 * mudança nos limites (minFpsPercentOfTarget, maxJitterMs, etc.) não passe
 * despercebida.
 */
class StabilityCriteriaTest {

    private fun snapshot(
        targetFps: Int = 165,
        encoderOutputFps: Double = 165.0,
        droppedFrames: Long = 0,
        jitterMs: Double = 1.0
    ) = FrameSnapshot(
        targetFps = targetFps,
        encoderOutputFps = encoderOutputFps,
        encoderThroughputFps = encoderOutputFps,
        captureFpsEstimate = 0.0,
        captureFpsIsReliable = false,
        droppedFrames = droppedFrames,
        duplicatedFrames = 0,
        delayedFrames = 0,
        jitterMs = jitterMs,
        elapsedMs = 10_000
    )

    private fun muxerStats(queuedBytes: Long = 0, drops: Long = 0) = MuxerStats(
        queuedSamples = 0,
        queuedBytes = queuedBytes,
        totalBytesWritten = 0,
        throughputBytesPerSec = 0.0,
        samplesDroppedByOverflow = drops,
        maxObservedQueueBytes = queuedBytes
    )

    @Test
    fun `configuracao perfeita e estavel`() {
        val verdict = StabilityCriteria().evaluate(snapshot(), muxerStats(), encoderHadError = false)
        assertTrue(verdict.stable)
        assertTrue(verdict.failureReasons.isEmpty())
    }

    @Test
    fun `fps abaixo de 97 por cento do alvo reprova`() {
        // 165 * 0.97 = 160.05 — 160.0 deve reprovar por uma margem mínima.
        val verdict = StabilityCriteria().evaluate(
            snapshot(targetFps = 165, encoderOutputFps = 160.0), muxerStats(), encoderHadError = false
        )
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("FPS médio real") })
    }

    @Test
    fun `frames perdidos acima do limite reprova`() {
        val verdict = StabilityCriteria(maxDroppedFrames = 5).evaluate(
            snapshot(droppedFrames = 6), muxerStats(), encoderHadError = false
        )
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("perdidos") })
    }

    @Test
    fun `jitter acima do limite reprova`() {
        val verdict = StabilityCriteria(maxJitterMs = 8.0).evaluate(
            snapshot(jitterMs = 9.0), muxerStats(), encoderHadError = false
        )
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("Jitter") })
    }

    @Test
    fun `erro do encoder sempre reprova mesmo com metricas boas`() {
        val verdict = StabilityCriteria().evaluate(snapshot(), muxerStats(), encoderHadError = true)
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("Encoder reportou erro") })
    }

    @Test
    fun `backlog do muxer acima do limite reprova`() {
        val verdict = StabilityCriteria(maxMuxerBacklogBytes = 10_000_000).evaluate(
            snapshot(), muxerStats(queuedBytes = 20_000_000), encoderHadError = false
        )
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("armazenamento não acompanha") })
    }

    @Test
    fun `qualquer descarte por overflow reprova por padrao`() {
        val verdict = StabilityCriteria().evaluate(
            snapshot(), muxerStats(drops = 1), encoderHadError = false
        )
        assertFalse(verdict.stable)
        assertTrue(verdict.failureReasons.any { it.contains("overflow") })
    }

    @Test
    fun `muxerStats nulo nao reprova por backlog ou overflow`() {
        // stats nulas (ex: muxer ainda não iniciou) não devem ser tratadas como
        // falha — StabilityCriteria trata null como "sem informação", não como
        // "backlog infinito".
        val verdict = StabilityCriteria().evaluate(snapshot(), null, encoderHadError = false)
        assertTrue(verdict.stable)
    }
}
