package com.famage.skydrop_tv_app.history

import android.content.Context
import android.util.Log
import com.famage.skydrop_tv_app.model.TvHistoryRecord
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.receiver.TvStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent History and Received Media Store for SkyDrop TV.
 *
 * Aligned with TransferHistoryStore from Android and Daemon architectures.
 */
class TvHistoryStore(
    private val baseDir: File,
    private val storageManager: TvStorageManager? = null,
    private val maxItems: Int = 200
) {
    constructor(context: Context, storageManager: TvStorageManager? = null, maxItems: Int = 200) :
            this(context.applicationContext.filesDir, storageManager, maxItems)

    companion object {
        private const val TAG = "TvHistoryStore"
        private const val HISTORY_FILE = "tv_transfer_history.json"
        private const val FILES_FILE = "tv_received_files.json"

        @Volatile
        private var instance: TvHistoryStore? = null

        fun getInstance(context: Context): TvHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: TvHistoryStore(
                    context.applicationContext,
                    TvStorageManager(context.applicationContext)
                ).also { instance = it }
            }
        }
    }

    private fun logError(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg ${tr?.message.orEmpty()}")
        }
    }

    private val historyFile: File by lazy { File(baseDir, HISTORY_FILE) }
    private val filesFile: File by lazy { File(baseDir, FILES_FILE) }

    private val _history = MutableStateFlow<List<TvHistoryRecord>>(emptyList())
    val history: StateFlow<List<TvHistoryRecord>> = _history.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<TvReceivedFile>>(emptyList())
    val receivedFiles: StateFlow<List<TvReceivedFile>> = _receivedFiles.asStateFlow()

    init {
        loadHistory()
        loadReceivedFiles()
    }

    @Synchronized
    fun addTransfer(record: TvHistoryRecord, file: TvReceivedFile? = null) {
        val currHist = _history.value.toMutableList()
        currHist.removeAll { it.id == record.id }
        currHist.add(0, record)
        val trimmedHist = if (currHist.size > maxItems) currHist.subList(0, maxItems) else currHist
        _history.value = trimmedHist
        saveHistory(trimmedHist)

        if (file != null) {
            val currFiles = _receivedFiles.value.toMutableList()
            currFiles.removeAll { it.id == file.id || it.name == file.name }
            currFiles.add(0, file)
            _receivedFiles.value = currFiles
            saveReceivedFiles(currFiles)
        }
    }

    @Synchronized
    fun removeFile(fileId: String) {
        val currFiles = _receivedFiles.value.toMutableList()
        currFiles.removeAll { it.id == fileId }
        _receivedFiles.value = currFiles
        saveReceivedFiles(currFiles)
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        saveHistory(emptyList())
    }

    @Synchronized
    private fun loadHistory() {
        if (!historyFile.exists()) {
            _history.value = emptyList()
            return
        }
        try {
            val jsonStr = historyFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<TvHistoryRecord>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    TvHistoryRecord(
                        id = obj.optString("id"),
                        timestamp = obj.optLong("timestamp"),
                        fileName = obj.optString("file_name"),
                        sizeBytes = obj.optLong("size_bytes"),
                        peerName = obj.optString("peer_name"),
                        isSuccessful = obj.optBoolean("is_successful", true),
                        direction = obj.optString("direction", "inbound"),
                        errorMessage = if (obj.has("error_message") && !obj.isNull("error_message")) obj.getString("error_message") else null
                    )
                )
            }
            _history.value = list
        } catch (e: Exception) {
            logError(TAG, "Failed to load transfer history", e)
            _history.value = emptyList()
        }
    }

    @Synchronized
    private fun saveHistory(items: List<TvHistoryRecord>) {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("file_name", item.fileName)
                    put("size_bytes", item.sizeBytes)
                    put("peer_name", item.peerName)
                    put("is_successful", item.isSuccessful)
                    put("direction", item.direction)
                    put("error_message", item.errorMessage)
                }
                jsonArray.put(obj)
            }
            historyFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            logError(TAG, "Failed to save transfer history", e)
        }
    }

    @Synchronized
    private fun loadReceivedFiles() {
        val list = mutableListOf<TvReceivedFile>()
        if (filesFile.exists()) {
            try {
                val jsonStr = filesFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        TvReceivedFile(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            sizeBytes = obj.optLong("size_bytes"),
                            mimeType = obj.optString("mime_type"),
                            senderName = obj.optString("sender_name"),
                            receivedAt = obj.optLong("received_at"),
                            filePath = obj.optString("file_path")
                        )
                    )
                }
            } catch (e: Exception) {
                logError(TAG, "Failed to parse received files json", e)
            }
        }

        // Merge any additional files discovered on disk
        storageManager?.scanDiskReceivedFiles()?.forEach { diskFile ->
            if (list.none { it.name == diskFile.name }) {
                list.add(diskFile)
            }
        }

        _receivedFiles.value = list.sortedByDescending { it.receivedAt }
    }

    @Synchronized
    private fun saveReceivedFiles(items: List<TvReceivedFile>) {
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("size_bytes", item.sizeBytes)
                    put("mime_type", item.mimeType)
                    put("sender_name", item.senderName)
                    put("received_at", item.receivedAt)
                    put("file_path", item.filePath)
                }
                jsonArray.put(obj)
            }
            filesFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            logError(TAG, "Failed to save received files", e)
        }
    }
}
