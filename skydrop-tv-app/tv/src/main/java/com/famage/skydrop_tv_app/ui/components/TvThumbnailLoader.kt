package com.famage.skydrop_tv_app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.*
import com.famage.skydrop_tv_app.model.TvReceivedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TvThumbnailLoader {
    private const val TAG = "TvThumbnailLoader"

    // Crisp high-density resolution bounds for 1080p and 4K TV displays
    private const val MAX_WIDTH = 640
    private const val MAX_HEIGHT = 380

    // Memory cache for decoded bitmaps
    private const val CACHE_SIZE = 50
    private val memoryCache = object : LruCache<String, Bitmap>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun get(filePath: String): Bitmap? = memoryCache.get(filePath)

    suspend fun loadThumbnail(file: TvReceivedFile): Bitmap? = withContext(Dispatchers.IO) {
        val path = file.filePath
        if (path.isBlank()) return@withContext null

        memoryCache.get(path)?.let { return@withContext it }

        val target = File(path)
        if (!target.exists() || !target.canRead()) return@withContext null

        val bitmap = try {
            when {
                TvMediaClassifier.isImage(file) -> decodeImageThumbnail(target)
                TvMediaClassifier.isVideo(file) -> extractVideoThumbnail(target)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail for ${file.name}: ${e.message}")
            null
        }

        if (bitmap != null) {
            memoryCache.put(path, bitmap)
        }
        bitmap
    }

    private fun decodeImageThumbnail(file: File): Bitmap? {
        val path = file.absolutePath
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)

        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) return null

        var sampleSize = 1
        while ((origW / sampleSize) > MAX_WIDTH || (origH / sampleSize) > MAX_HEIGHT) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888 // 32-bit true color eliminates distortion & banding
        }

        val decoded = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

        // Correct EXIF orientation so phone camera pictures are never shown rotated sideways or stretched
        val rotationDegrees = try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) {
                decoded.recycle()
            }
            rotated
        } else {
            decoded
        }
    }

    private fun extractVideoThumbnail(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            // API 27+ supports aspect-ratio-safe high-res frame retrieval
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MAX_WIDTH,
                    MAX_HEIGHT
                ) ?: retriever.frameAtTime
            } else {
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            }

            if (raw != null && (raw.width > MAX_WIDTH || raw.height > MAX_HEIGHT)) {
                val scale = (MAX_WIDTH.toFloat() / raw.width).coerceAtMost(MAX_HEIGHT.toFloat() / raw.height)
                val targetW = (raw.width * scale).toInt().coerceAtLeast(1)
                val targetH = (raw.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                if (scaled != raw) {
                    raw.recycle()
                }
                scaled
            } else {
                raw
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun rememberTvThumbnail(file: TvReceivedFile): Bitmap? {
    var bitmap by remember(file.filePath) { mutableStateOf(TvThumbnailLoader.get(file.filePath)) }

    if (bitmap == null && (TvMediaClassifier.isImage(file) || TvMediaClassifier.isVideo(file))) {
        LaunchedEffect(file.filePath) {
            bitmap = TvThumbnailLoader.loadThumbnail(file)
        }
    }

    return bitmap
}
