package com.screenrec.pro.core.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private data class MuxSample(
    val trackIndex: Int,
    val data: ByteBuffer,
    val presentationTimeUs: Long,
    val flags: Int,
    val byteSize: Int
)

data class MuxerStats(
    val queuedSamples: Int,
    val queuedBytes: Long,
    val totalBytesWritten: Long,
    val throughputBytesPerSec: Double,
    val samplesDroppedByOverflow: Long,
    val maxObservedQueueBytes: Long
)

/**
 * AUDITORIA (revisão 2), item 9: a versão anterior usava uma LinkedBlockingQueue
 * sem limite — sob storage lento a 200-500 Mbps, isso permitiria a fila (e a RAM)
 * crescer indefinidamente até o OOM, mascarando o problema real (I/O não
 * acompanha o bitrate) atrás de um sintoma pior (crash por memória).
 *
 * Mudanças:
 *  - Orçamento de bytes fixo para a fila (padrão 96 MB — ~2-3s de buffer a 300 Mbps).
 *  - Ao atingir o limite, a amostra mais ANTIGA de VÍDEO é descartada (áudio tem
 *    prioridade de retenção por ser muito mais leve e crítico p/ sincronismo);
 *    o descarte é contado, nunca silencioso.
 *  - Métricas de backlog (amostras enfileiradas, bytes enfileirados, throughput
 *    real de escrita) expostas via StateFlow para a UI mostrar "Muxer queue" e
 *    "Storage throughput" como pedido.
 */
class MuxerManager(
    outputPath: String,
    private val expectsAudio: Boolean,
    private val maxQueueBytes: Long = 96L * 1024 * 1024,
    containerFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
) {
    private val muxer = MediaMuxer(outputPath, containerFormat)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var started = false
    private var videoFormatSet = false
    private var audioFormatSet = false

    // Deque (não Queue simples) porque o descarte por overflow precisa remover do
    // início preservando ordem de chegada dos elementos restantes.
    private val queue = LinkedBlockingDeque<MuxSample>()
    private val queuedBytes = AtomicLong(0)
    private val totalBytesWritten = AtomicLong(0)
    private val samplesDroppedByOverflow = AtomicLong(0)
    private val maxObservedQueueBytes = AtomicLong(0)

    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null

    private val _stats = MutableStateFlow(MuxerStats(0, 0, 0, 0.0, 0, 0))
    val stats: StateFlow<MuxerStats> = _stats

    private var lastThroughputSampleNs = 0L
    private var lastThroughputSampleBytes = 0L

    fun addVideoTrack(format: MediaFormat) {
        videoTrackIndex = muxer.addTrack(format)
        videoFormatSet = true
        maybeStart()
    }

    fun addAudioTrack(format: MediaFormat) {
        audioTrackIndex = muxer.addTrack(format)
        audioFormatSet = true
        maybeStart()
    }

    private fun maybeStart() {
        if (started) return
        val audioReady = !expectsAudio || audioFormatSet
        if (videoFormatSet && audioReady) {
            muxer.start()
            started = true
            running.set(true)
            lastThroughputSampleNs = System.nanoTime()
            writerThread = thread(name = "MuxerWriterThread") { writeLoop() }
        }
    }

    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        enqueue(MuxSample(videoTrackIndex, cloneBuffer(buffer, info), info.presentationTimeUs, info.flags, info.size), isVideo = true)
    }

    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || audioTrackIndex < 0) return
        enqueue(MuxSample(audioTrackIndex, cloneBuffer(buffer, info), info.presentationTimeUs, info.flags, info.size), isVideo = false)
    }

    private fun enqueue(sample: MuxSample, isVideo: Boolean) {
        // Orçamento de bytes estourado: descarta a amostra de VÍDEO mais antiga da
        // fila (não a que acabou de chegar) para abrir espaço, e conta o descarte.
        // Isso limita a RAM de forma dura, ao custo de um frame perdido sob
        // storage genuinamente incapaz de sustentar o bitrate configurado — o que
        // é exatamente o cenário que o item 9 pediu para nunca mascarar.
        while (queuedBytes.get() + sample.byteSize > maxQueueBytes) {
            val victim = queue.firstOrNull { it.trackIndex == videoTrackIndex } ?: queue.pollFirst() ?: break
            if (queue.remove(victim)) {
                queuedBytes.addAndGet(-victim.byteSize.toLong())
                samplesDroppedByOverflow.incrementAndGet()
            } else {
                break
            }
        }
        queue.addLast(sample)
        val newTotal = queuedBytes.addAndGet(sample.byteSize.toLong())
        maxObservedQueueBytes.updateAndGet { maxOf(it, newTotal) }
        publishStats()
    }

    private fun cloneBuffer(source: ByteBuffer, info: MediaCodec.BufferInfo): ByteBuffer {
        val copy = ByteBuffer.allocateDirect(info.size)
        val duped = source.duplicate()
        duped.position(info.offset)
        duped.limit(info.offset + info.size)
        copy.put(duped)
        copy.flip()
        return copy
    }

    private fun writeLoop() {
        while (running.get() || queue.isNotEmpty()) {
            val sample = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            queuedBytes.addAndGet(-sample.byteSize.toLong())
            try {
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.data.remaining(), sample.presentationTimeUs, sample.flags)
                }
                muxer.writeSampleData(sample.trackIndex, sample.data, info)
                totalBytesWritten.addAndGet(sample.byteSize.toLong())
            } catch (e: Exception) {
                android.util.Log.e("MuxerManager", "Falha ao escrever amostra: ${e.message}")
            }
            publishStats()
        }
    }

    private fun publishStats() {
        val nowNs = System.nanoTime()
        val elapsedSec = (nowNs - lastThroughputSampleNs) / 1_000_000_000.0
        var throughput = 0.0
        if (elapsedSec >= 0.5) {
            val bytesDelta = totalBytesWritten.get() - lastThroughputSampleBytes
            throughput = bytesDelta / elapsedSec
            lastThroughputSampleNs = nowNs
            lastThroughputSampleBytes = totalBytesWritten.get()
        }
        _stats.value = MuxerStats(
            queuedSamples = queue.size,
            queuedBytes = queuedBytes.get(),
            totalBytesWritten = totalBytesWritten.get(),
            throughputBytesPerSec = if (throughput > 0.0) throughput else _stats.value.throughputBytesPerSec,
            samplesDroppedByOverflow = samplesDroppedByOverflow.get(),
            maxObservedQueueBytes = maxObservedQueueBytes.get()
        )
    }

    fun stop() {
        running.set(false)
        writerThread?.join(2000)
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            android.util.Log.e("MuxerManager", "Erro ao parar muxer: ${e.message}")
        } finally {
            muxer.release()
        }
    }

    fun pendingQueueSize(): Int = queue.size
}
