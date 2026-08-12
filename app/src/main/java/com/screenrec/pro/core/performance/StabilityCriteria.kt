package com.screenrec.pro.core.performance

/**
 * Item 14 da auditoria: critérios objetivos e DOCUMENTADOS para decidir se uma
 * configuração é "estável", usados tanto pelo BenchmarkRunner quanto pela
 * gravação real. Configuráveis via construtor — os valores padrão abaixo são os
 * usados pelo app, mas nada aqui é implícito ou hardcoded sem explicação.
 */
data class StabilityCriteria(
    /** FPS médio real (via PTS do encoder) precisa ser >= este percentual do alvo. */
    val minFpsPercentOfTarget: Double = 0.97,
    /** Frames considerados "perdidos" (gap de PTS) tolerados na janela do teste. */
    val maxDroppedFrames: Long = 5,
    /** Jitter máximo aceitável entre frames consecutivos, em milissegundos. */
    val maxJitterMs: Double = 8.0,
    /** Backlog máximo tolerado na fila do muxer, em bytes, antes de considerar
     *  que o armazenamento não está acompanhando o bitrate. */
    val maxMuxerBacklogBytes: Long = 48L * 1024 * 1024,
    /** Descartes por overflow do muxer tolerados. */
    val maxMuxerOverflowDrops: Long = 0
) {
    fun evaluate(
        snapshot: FrameSnapshot,
        muxerStats: com.screenrec.pro.core.muxer.MuxerStats?,
        encoderHadError: Boolean
    ): StabilityVerdict {
        val reasons = mutableListOf<String>()

        val fpsOk = snapshot.encoderOutputFps >= snapshot.targetFps * minFpsPercentOfTarget
        if (!fpsOk) reasons.add("FPS médio real (${"%.1f".format(snapshot.encoderOutputFps)}) abaixo de ${(minFpsPercentOfTarget * 100).toInt()}% do alvo (${snapshot.targetFps})")

        val dropsOk = snapshot.droppedFrames <= maxDroppedFrames
        if (!dropsOk) reasons.add("${snapshot.droppedFrames} frames perdidos (limite: $maxDroppedFrames)")

        val jitterOk = snapshot.jitterMs <= maxJitterMs
        if (!jitterOk) reasons.add("Jitter de ${"%.1f".format(snapshot.jitterMs)}ms acima do limite de ${maxJitterMs}ms")

        val encoderOk = !encoderHadError
        if (!encoderOk) reasons.add("Encoder reportou erro durante o teste")

        val backlogOk = muxerStats == null || muxerStats.queuedBytes <= maxMuxerBacklogBytes
        if (!backlogOk) reasons.add("Fila do muxer atingiu ${muxerStats!!.queuedBytes / 1_048_576}MB (limite: ${maxMuxerBacklogBytes / 1_048_576}MB) — armazenamento não acompanha o bitrate")

        val overflowOk = muxerStats == null || muxerStats.samplesDroppedByOverflow <= maxMuxerOverflowDrops
        if (!overflowOk) reasons.add("${muxerStats!!.samplesDroppedByOverflow} amostras descartadas por overflow do muxer")

        val stable = fpsOk && dropsOk && jitterOk && encoderOk && backlogOk && overflowOk
        return StabilityVerdict(stable, reasons)
    }
}

data class StabilityVerdict(val stable: Boolean, val failureReasons: List<String>)
