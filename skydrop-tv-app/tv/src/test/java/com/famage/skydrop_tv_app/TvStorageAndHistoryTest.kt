package com.famage.skydrop_tv_app

import com.famage.skydrop_tv_app.history.TvHistoryStore
import com.famage.skydrop_tv_app.model.TvHistoryRecord
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.receiver.TvStorageManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TvStorageAndHistoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFilenameSanitization() {
        assertEquals("vacation.mp4", TvStorageManager.sanitizeFilename("vacation.mp4"))
        assertEquals("report.pdf", TvStorageManager.sanitizeFilename("../../../report.pdf"))
        assertEquals("test_file.png", TvStorageManager.sanitizeFilename("some/deep/path/test_file.png"))
        assertEquals("windows_style.mkv", TvStorageManager.sanitizeFilename("C:\\windows\\windows_style.mkv"))
        assertTrue(TvStorageManager.sanitizeFilename("").startsWith("received_file_"))
        assertTrue(TvStorageManager.sanitizeFilename("   ").startsWith("received_file_"))
    }

    @Test
    fun testMimeTypeResolution() {
        assertEquals("video/mp4", TvStorageManager.getMimeType("sample.mp4"))
        assertEquals("video/mkv", TvStorageManager.getMimeType("movie.mkv"))
        assertEquals("image/png", TvStorageManager.getMimeType("photo.png"))
        assertEquals("image/jpeg", TvStorageManager.getMimeType("image.jpg"))
        assertEquals("audio/mp3", TvStorageManager.getMimeType("song.mp3"))
        assertEquals("application/pdf", TvStorageManager.getMimeType("doc.pdf"))
        assertEquals("application/vnd.android.package-archive", TvStorageManager.getMimeType("app.apk"))
        assertEquals("application/octet-stream", TvStorageManager.getMimeType("unknown.xyz123"))
    }

    @Test
    fun testHistoryStorePersistence() {
        val baseDir = tempFolder.newFolder("history_test")
        val store = TvHistoryStore(baseDir = baseDir, maxItems = 10)

        val record1 = TvHistoryRecord(
            id = "rec-1",
            timestamp = 1000L,
            fileName = "video.mp4",
            sizeBytes = 1048576L,
            peerName = "Samsung SM-A556E",
            isSuccessful = true,
            direction = "inbound"
        )
        val file1 = TvReceivedFile(
            id = "file-1",
            name = "video.mp4",
            sizeBytes = 1048576L,
            mimeType = "video/mp4",
            senderName = "Samsung SM-A556E",
            receivedAt = 1000L,
            filePath = "/sdcard/Download/SkyDrop/video.mp4"
        )

        store.addTransfer(record1, file1)

        assertEquals(1, store.history.value.size)
        assertEquals("video.mp4", store.history.value[0].fileName)
        assertEquals(1, store.receivedFiles.value.size)
        assertEquals("video.mp4", store.receivedFiles.value[0].name)

        // Reload store from disk and assert persistence
        val reloadedStore = TvHistoryStore(baseDir = baseDir, maxItems = 10)
        assertEquals(1, reloadedStore.history.value.size)
        assertEquals("video.mp4", reloadedStore.history.value[0].fileName)
        assertEquals(1, reloadedStore.receivedFiles.value.size)
        assertEquals("video.mp4", reloadedStore.receivedFiles.value[0].name)

        // Test deletion
        reloadedStore.removeFile("file-1")
        assertEquals(0, reloadedStore.receivedFiles.value.size)
    }
}
