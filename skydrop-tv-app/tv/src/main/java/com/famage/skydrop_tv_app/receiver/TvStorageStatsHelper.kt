package com.famage.skydrop_tv_app.receiver

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import com.famage.skydrop_tv_app.model.TvRemovableMedia
import com.famage.skydrop_tv_app.model.TvStorageOverview
import com.famage.skydrop_tv_app.model.TvStorageSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Helper to inspect internal storage and detect any mounted removable storage media (USB/SD).
 */
object TvStorageStatsHelper {

    private const val TAG = "TvStorageStatsHelper"

    /**
     * Reads internal storage space via StatFs.
     */
    fun queryInternalStorageSpace(): TvStorageSpace {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val total = stat.blockCountLong * blockSize
            val free = stat.availableBlocksLong * blockSize
            TvStorageSpace(totalBytes = total, freeBytes = free)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect internal storage stats", e)
            TvStorageSpace(totalBytes = 0L, freeBytes = 0L)
        }
    }

    /**
     * Queries connected removable media (USB drives, SD cards, external HDDs).
     * If no media is plugged in, returns an empty list.
     */
    fun queryRemovableMedia(context: Context): List<TvRemovableMedia> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val results = mutableListOf<TvRemovableMedia>()
        val seenPaths = mutableSetOf<String>()

        // 1. Check via StorageManager storageVolumes (API 24+)
        if (storageManager != null) {
            try {
                val volumes: List<StorageVolume> = storageManager.storageVolumes
                for (volume in volumes) {
                    if (volume.isRemovable && isVolumeMounted(volume)) {
                        val rawLabel = volume.getDescription(context)
                        val label = when {
                            !rawLabel.isNullOrBlank() -> rawLabel
                            !volume.uuid.isNullOrBlank() -> "USB (${volume.uuid})"
                            else -> "USB Drive"
                        }

                        var totalBytes = 0L
                        var freeBytes = 0L
                        var path = ""

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val dir = volume.directory
                            if (dir != null) {
                                path = dir.absolutePath
                                try {
                                    val stat = StatFs(dir.path)
                                    totalBytes = stat.blockCountLong * stat.blockSizeLong
                                    freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                                } catch (e: Exception) {
                                    Log.w(TAG, "StatFs on volume directory failed: $path", e)
                                }
                            }
                        }

                        // Fallback via getExternalFilesDirs if statfs was 0
                        if (totalBytes <= 0L && volume.uuid != null) {
                            val matchingDir = findExternalDirForVolume(context, volume.uuid)
                            if (matchingDir != null) {
                                try {
                                    val stat = StatFs(matchingDir.path)
                                    totalBytes = stat.blockCountLong * stat.blockSizeLong
                                    freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                                    if (path.isEmpty()) path = matchingDir.path
                                } catch (_: Exception) {}
                            }
                        }

                        val id = volume.uuid ?: path.ifEmpty { "removable_${results.size + 1}" }
                        if (path.isNotEmpty()) seenPaths.add(path)

                        results.add(
                            TvRemovableMedia(
                                id = id,
                                name = label,
                                path = path,
                                space = TvStorageSpace(totalBytes = totalBytes, freeBytes = freeBytes)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying StorageManager volumes", e)
            }
        }

        // 2. Secondary check via ContextCompat.getExternalFilesDirs for any mounted removable storage
        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (dir in externalDirs) {
                if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                    val rootPath = extractVolumeRoot(dir.absolutePath)
                    if (rootPath != null && !seenPaths.contains(rootPath)) {
                        try {
                            val stat = StatFs(dir.path)
                            val total = stat.blockCountLong * stat.blockSizeLong
                            val free = stat.availableBlocksLong * stat.blockSizeLong
                            if (total > 0L) {
                                seenPaths.add(rootPath)
                                val volumeName = File(rootPath).name
                                results.add(
                                    TvRemovableMedia(
                                        id = volumeName,
                                        name = "USB Storage ($volumeName)",
                                        path = rootPath,
                                        space = TvStorageSpace(totalBytes = total, freeBytes = free)
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed querying removable dir: ${dir.path}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying external files dirs", e)
        }

        return results
    }

    private fun isVolumeMounted(volume: StorageVolume): Boolean {
        val state = volume.state
        return state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    }

    private fun findExternalDirForVolume(context: Context, uuid: String?): File? {
        if (uuid == null) return null
        return try {
            val dirs = ContextCompat.getExternalFilesDirs(context, null)
            dirs.firstOrNull { it != null && it.absolutePath.contains(uuid, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVolumeRoot(path: String): String? {
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx != -1) path.substring(0, idx) else null
    }

    /**
     * Loads complete storage overview asynchronously.
     */
    suspend fun getStorageOverview(context: Context): TvStorageOverview = withContext(Dispatchers.IO) {
        val internal = queryInternalStorageSpace()
        val removable = queryRemovableMedia(context)
        TvStorageOverview(
            internalStorage = internal,
            removableMedia = removable
        )
    }
}
