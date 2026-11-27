package com.famage.skydrop_tv_app.model

import java.util.Locale

/**
 * Byte-level storage metrics with human-readable formatting and usage percentages.
 */
data class TvStorageSpace(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long = (totalBytes - freeBytes).coerceAtLeast(0L)
) {
    val usedPercent: Int
        get() = if (totalBytes > 0L) {
            ((usedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

    val totalFormatted: String
        get() = formatBytes(totalBytes)

    val usedFormatted: String
        get() = formatBytes(usedBytes)

    val freeFormatted: String
        get() = formatBytes(freeBytes)

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return if (digitGroups == 0) {
                "${bytes} B"
            } else {
                String.format(Locale.US, "%.1f %s", value, units[digitGroups])
            }
        }
    }
}

/**
 * Details for removable media (USB drive, external hard drive, SD card).
 */
data class TvRemovableMedia(
    val id: String,
    val name: String,
    val path: String,
    val space: TvStorageSpace
)

/**
 * Complete storage snapshot of the TV.
 */
data class TvStorageOverview(
    val internalStorage: TvStorageSpace,
    val removableMedia: List<TvRemovableMedia> = emptyList()
)
