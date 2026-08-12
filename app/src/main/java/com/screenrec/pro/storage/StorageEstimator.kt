package com.screenrec.pro.storage

import android.os.StatFs

data class StorageEstimate(
    val perMinuteBytes: Long,
    val per5MinBytes: Long,
    val per10MinBytes: Long,
    val per30MinBytes: Long,
    val per1HourBytes: Long
)

object StorageEstimator {

    /** Overhead de container/muxer sobre o bitrate bruto (áudio+vídeo). ~4% é uma
     *  aproximação razoável para MP4 com poucos keyframes; documentado, não fingido
     *  como exato. */
    private const val CONTAINER_OVERHEAD_FACTOR = 1.04

    fun estimate(videoBitrateBps: Int, audioBitrateBps: Int): StorageEstimate {
        val totalBytesPerSecond = ((videoBitrateBps + audioBitrateBps) / 8.0 * CONTAINER_OVERHEAD_FACTOR)
        val perMinute = (totalBytesPerSecond * 60).toLong()
        return StorageEstimate(
            perMinuteBytes = perMinute,
            per5MinBytes = perMinute * 5,
            per10MinBytes = perMinute * 10,
            per30MinBytes = perMinute * 30,
            per1HourBytes = perMinute * 60
        )
    }

    fun availableBytes(path: String): Long {
        return try {
            val stat = StatFs(path)
            stat.availableBytes
        } catch (e: Exception) {
            -1L
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "desconhecido"
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(bytes / 1_048_576.0)
    }

    /** Retorna true se o espaço restante ficará abaixo da margem de segurança
     *  (2 minutos de gravação no bitrate atual, ou 200MB, o que for maior). */
    fun isStorageCritical(availableBytes: Long, videoBitrateBps: Int, audioBitrateBps: Int): Boolean {
        if (availableBytes < 0) return false
        val estimate = estimate(videoBitrateBps, audioBitrateBps)
        val twoMinutesMargin = estimate.perMinuteBytes * 2
        val safetyMargin = maxOf(twoMinutesMargin, 200L * 1_048_576)
        return availableBytes < safetyMargin
    }
}
