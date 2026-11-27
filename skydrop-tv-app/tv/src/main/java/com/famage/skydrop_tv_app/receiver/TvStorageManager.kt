package com.famage.skydrop_tv_app.receiver

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.famage.skydrop_tv_app.model.TvReceivedFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class TvSaveResult(
    val uri: Uri,
    val bytesWritten: Long,
    val mimeType: String,
    val filePath: String
)

class TvStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "TvStorageManager"
        private const val CHUNK_SIZE = 64 * 1024
        const val SUBFOLDER = "SkyDrop"

        fun sanitizeFilename(name: String): String {
            val normalized = name.trim().replace("\\", "/")
            val clean = File(normalized).name
                .replace("/", "_")
                .replace("..", "_")
                .trim()
            return if (clean.isBlank()) "received_file_${System.currentTimeMillis()}" else clean
        }

        fun getMimeType(filename: String): String {
            val ext = File(filename).extension.lowercase()
            val androidMime = try {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
            } catch (_: Throwable) {
                null
            }
            return androidMime ?: when (ext) {
                "mp4", "mkv", "avi", "webm", "mov" -> "video/$ext"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "mp3" -> "audio/mp3"
                "flac", "wav", "aac", "ogg", "m4a" -> "audio/$ext"
                "pdf" -> "application/pdf"
                "apk" -> "application/vnd.android.package-archive"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }

    private val contentResolver: ContentResolver = context.contentResolver

    fun saveStreamWithResult(
        rawFilename: String,
        inputStream: InputStream,
        declaredSize: Long,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): Result<TvSaveResult> {
        val sanitizedName = sanitizeFilename(rawFilename)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreResult = saveWithMediaStore(sanitizedName, inputStream, onProgress)
            if (mediaStoreResult.isSuccess) {
                mediaStoreResult
            } else {
                Log.w(TAG, "MediaStore write failed, falling back to legacy storage", mediaStoreResult.exceptionOrNull())
                saveWithLegacyStorage(sanitizedName, inputStream, onProgress)
            }
        } else {
            saveWithLegacyStorage(sanitizedName, inputStream, onProgress)
        }
    }

    private fun saveWithMediaStore(
        filename: String,
        inputStream: InputStream,
        onProgress: ((bytesWritten: Long) -> Unit)?
    ): Result<TvSaveResult> {
        val mimeType = getMimeType(filename)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: return Result.failure(IOException("Failed to create MediaStore entry for $filename"))

        try {
            var bytesWritten = 0L
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val buffer = ByteArray(CHUNK_SIZE)
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten)
                }
                outputStream.flush()
            } ?: throw IOException("Failed to open output stream for MediaStore URI: $uri")

            // Mark file as complete and visible to other media players
            val updateValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            contentResolver.update(uri, updateValues, null, null)

            val displayPath = "/storage/emulated/0/Download/$SUBFOLDER/$filename"
            return Result.success(TvSaveResult(uri, bytesWritten, mimeType, displayPath))
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming to MediaStore, cleaning up pending entry", e)
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
            return Result.failure(e)
        }
    }

    private fun saveWithLegacyStorage(
        filename: String,
        inputStream: InputStream,
        onProgress: ((bytesWritten: Long) -> Unit)?
    ): Result<TvSaveResult> {
        return try {
            val mimeType = getMimeType(filename)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir

            val targetDir = File(downloadsDir, SUBFOLDER).apply { mkdirs() }
            val tempFile = File.createTempFile(".tv-drop-", ".tmp", targetDir)
            val finalFile = File(targetDir, filename)

            var bytesWritten = 0L
            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(CHUNK_SIZE)
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten)
                }
                fos.flush()
            }

            if (finalFile.exists()) {
                finalFile.delete()
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), arrayOf(mimeType), null)
            Result.success(TvSaveResult(Uri.fromFile(finalFile), bytesWritten, mimeType, finalFile.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Direct storage write failed", e)
            Result.failure(e)
        }
    }

    /**
     * Scans the SkyDrop download directory to detect previously received files.
     */
    fun scanDiskReceivedFiles(): List<TvReceivedFile> {
        val list = mutableListOf<TvReceivedFile>()
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

            val targetDir = File(downloadsDir, SUBFOLDER)
            if (targetDir.exists() && targetDir.isDirectory) {
                targetDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { file ->
                    list.add(
                        TvReceivedFile(
                            id = file.name + "_" + file.lastModified(),
                            name = file.name,
                            sizeBytes = file.length(),
                            mimeType = getMimeType(file.name),
                            senderName = "Peer Drop",
                            receivedAt = file.lastModified(),
                            filePath = file.absolutePath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning disk files", e)
        }
        return list.sortedByDescending { it.receivedAt }
    }
}
