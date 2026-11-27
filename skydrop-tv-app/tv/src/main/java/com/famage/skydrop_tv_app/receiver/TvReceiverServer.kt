package com.famage.skydrop_tv_app.receiver

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.famage.skydrop_tv_app.history.TvHistoryStore
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.IncomingPairRequest
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import com.famage.skydrop_tv_app.model.TvHistoryRecord
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.security.TvPairedDeviceStore
import com.famage.skydrop_tv_app.security.TvPairingManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Embedded HTTP Receiver Server for SkyDrop TV.
 *
 * Runs non-blocking coroutine-based request handlers implementing the SkyDrop v1 protocol:
 * - GET  /info                 -> Public node discovery & identity
 * - GET  /health               -> Authenticated node health check
 * - POST /pair/request         -> Handles incoming connection requests
 * - GET  /pair/request/status  -> Polls request approval state
 * - POST /pair/request/cancel  -> Cancels or declines a pair request
 * - POST /pair/start           -> Generates ephemeral local pairing session
 * - POST /pair/confirm         -> Validates PIN secret and establishes persistent pairing
 * - HEAD /upload               -> Resumable upload offset query
 * - POST /upload               -> Streams file bytes directly to storage
 * - POST /text                 -> Handles shared text/clipboard drops
 */
class TvReceiverServer(
    private val context: Context,
    val port: Int = 8444,
    private val pairedDeviceStore: TvPairedDeviceStore,
    val storageManager: TvStorageManager = TvStorageManager(context),
    val historyStore: TvHistoryStore = TvHistoryStore.getInstance(context)
) {
    companion object {
        private const val TAG = "TvReceiverServer"
        private const val MAX_HEADER_BYTES = 65536
        private const val MAX_BODY_BYTES = 65536
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isTransferring: Boolean = false
        private set

    var onFileReceived: ((TvReceivedFile) -> Unit)? = null
    var onIncomingTransferStarted: ((filename: String, senderName: String, totalBytes: Long) -> Unit)? = null
    var onReceiveProgress: ((filename: String, bytesRead: Long, totalBytes: Long, percent: Int) -> Unit)? = null
    var onTextReceived: ((text: String, senderName: String) -> Unit)? = null

    @Synchronized
    fun start(): Int {
        if (serverSocket != null) return serverSocket?.localPort ?: port

        try {
            val socket = ServerSocket(port)
            serverSocket = socket
            val actualPort = socket.localPort
            isRunning = true

            serverJob = scope.launch {
                Log.i(TAG, "TvReceiverServer listening on port $actualPort")
                while (isActive) {
                    try {
                        val client = socket.accept()
                        launch { handleConnection(client) }
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Accept error", e)
                    }
                }
                isRunning = false
            }
            return actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TvReceiverServer on port $port", e)
            isRunning = false
            return port
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "TvReceiverServer stopped")
    }

    private suspend fun handleConnection(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.use { sock ->
                sock.soTimeout = 30000 // 30s timeout for large file drops
                val rawInput = sock.getInputStream()
                val output = sock.getOutputStream()

                val headerLines = mutableListOf<String>()
                val headerBuilder = StringBuilder()
                var prev = -1
                var curr: Int
                var totalHeaderBytes = 0

                while (rawInput.read().also { curr = it } != -1) {
                    totalHeaderBytes++
                    if (totalHeaderBytes > MAX_HEADER_BYTES) {
                        Log.w(TAG, "Header limit exceeded, closing socket")
                        return@use
                    }
                    headerBuilder.append(curr.toChar())
                    if (prev == '\r'.code && curr == '\n'.code) {
                        val line = headerBuilder.toString().trim()
                        headerBuilder.clear()
                        if (line.isEmpty()) break
                        headerLines.add(line)
                    }
                    prev = curr
                }

                if (headerLines.isEmpty()) return@use

                val requestLine = headerLines[0].split(" ")
                if (requestLine.size < 2) return@use

                val method = requestLine[0].uppercase()
                val path = requestLine[1]
                Log.i(TAG, "Incoming HTTP $method $path from ${sock.inetAddress?.hostAddress}")

                val headers = mutableMapOf<String, String>()
                for (i in 1 until headerLines.size) {
                    val idx = headerLines[i].indexOf(':')
                    if (idx > 0) {
                        val k = headerLines[i].substring(0, idx).trim().lowercase()
                        val v = headerLines[i].substring(idx + 1).trim()
                        headers[k] = v
                    }
                }

                when {
                    method == "GET" && path == "/info" -> handleInfo(output)
                    method == "GET" && path == "/health" -> handleHealth(headers, output)
                    method == "POST" && path == "/pair/request" -> handlePairRequest(sock, headers, rawInput, output)
                    method == "GET" && path.startsWith("/pair/request/status") -> handlePairRequestStatus(path, output)
                    method == "POST" && path == "/pair/request/cancel" -> handlePairRequestCancel(headers, rawInput, output)
                    method == "POST" && path == "/pair/start" -> handlePairStart(output)
                    method == "POST" && path == "/pair/confirm" -> handlePairConfirm(sock, headers, rawInput, output)
                    method == "POST" && path == "/presence/leave" -> handlePresenceLeave(output)
                    method == "HEAD" && (path == "/upload" || path.startsWith("/upload?")) -> handleUploadHead(headers, output)
                    method == "POST" && (path == "/upload" || path.startsWith("/upload?")) -> handleUpload(sock, headers, rawInput, output)
                    method == "POST" && path == "/text" -> handleText(headers, rawInput, output)
                    else -> sendResponse(output, 404, "Not Found", "{\"error\":\"not_found\"}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection error: ${e.message}")
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.44"
        } catch (_: Exception) {
            "1.0.44"
        }
    }

    private fun handleInfo(output: OutputStream) {
        val devName = TvIdentityManager.getDeviceName()
        val json = JSONObject().apply {
            put("device_name", devName)
            put("version", getAppVersion())
            put("os", "android-tv")
            put("protocol", "skydrop/v1")
            put("tls_enabled", false)
            put("fingerprint", "")
        }.toString()
        sendResponse(output, 200, "OK", json)
    }

    private fun handleHealth(headers: Map<String, String>, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        val pairedList = pairedDeviceStore.pairedDevices.value
        if (pairedList.isNotEmpty()) {
            val isAuthorized = pairedList.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"ERR_AUTH_FAILED\",\"message\":\"Device not paired or token invalid\"}")
                return
            }
        }

        val devName = TvIdentityManager.getDeviceName()
        val devId = TvIdentityManager.getDeviceId(context)
        val capsArray = JSONArray().apply {
            put("health_v1")
            put("resumable_upload")
            put("checksum_v1")
            put("batch_transfer")
        }
        val json = JSONObject().apply {
            put("device_id", devId)
            put("device_name", devName)
            put("app_version", getAppVersion())
            put("protocol_version", "skydrop/v1")
            put("capabilities", capsArray)
            put("state", if (isTransferring) "transferring" else "idle")
            put("fingerprint", "")
        }.toString()
        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairRequest(
        sock: Socket,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val bodyStr = readBodyString(headers, input)
        try {
            val json = JSONObject(bodyStr)
            val remoteName = json.optString("device_name", "Remote Peer")
            val rawReqId = json.optString("request_id")
            val reqId = if (rawReqId.isNotBlank()) rawReqId else UUID.randomUUID().toString()
            val rawDevId = json.optString("device_id")
            val devId = if (rawDevId.isNotBlank()) rawDevId else UUID.randomUUID().toString()
            val devType = json.optString("device_type", "phone")
            val remoteIp = sock.inetAddress?.hostAddress ?: ""
            val host = json.optString("host", remoteIp).ifBlank { remoteIp }
            val port = json.optInt("port", 8444)
            val isTls = json.optBoolean("is_tls", port == 8443)
            val fp = json.optString("fingerprint", "")

            val request = IncomingPairRequest(
                requestId = reqId,
                deviceId = devId,
                deviceName = remoteName,
                deviceType = devType,
                host = host,
                port = port,
                isTls = isTls,
                fingerprint = fp,
                expiresAtMs = System.currentTimeMillis() + 90000L,
                status = "pending"
            )

            TvPairingManager.addIncomingRequest(request)

            val resp = JSONObject().apply {
                put("status", "pending")
                put("request_id", reqId)
                put("expires_at", request.expiresAtMs.toString())
            }.toString()

            sendResponse(output, 200, "OK", resp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pair request", e)
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Failed to parse request JSON\"}")
        }
    }

    private fun handlePairRequestStatus(path: String, output: OutputStream) {
        val reqId = if (path.contains("request_id=")) {
            path.substringAfter("request_id=").substringBefore("&")
        } else ""

        val req = TvPairingManager.pendingIncomingRequests[reqId]
        if (req == null) {
            sendResponse(output, 404, "Not Found", "{\"error\":\"not_found\",\"message\":\"Request not found\"}")
            return
        }

        if (req.status == "pending" && System.currentTimeMillis() > req.expiresAtMs) {
            req.status = "expired"
            TvPairingManager.updateIncomingFlow()
        }

        val devName = TvIdentityManager.getDeviceName()
        val json = JSONObject().apply {
            put("request_id", req.requestId)
            put("status", req.status)
            put("device_name", devName)
            put("fingerprint", "")
        }.toString()

        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairRequestCancel(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val bodyStr = readBodyString(headers, input)
        var reqId = ""
        if (bodyStr.isNotBlank()) {
            try {
                val json = JSONObject(bodyStr)
                reqId = json.optString("request_id")
            } catch (_: Exception) {}
        }
        val req = TvPairingManager.cancelIncomingRequest(reqId)
        val json = JSONObject().apply {
            put("status", req?.status ?: "cancelled")
            put("request_id", reqId)
        }.toString()
        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairStart(output: OutputStream) {
        val session = TvPairingManager.generateLocalSession()
        val devName = TvIdentityManager.getDeviceName()
        val pairingURI = "skydrop://pair?device=${java.net.URLEncoder.encode(devName, "UTF-8")}&host=&port=$port&secret=${session.secret}&fingerprint="

        val json = JSONObject().apply {
            put("status", "ready")
            put("device_name", devName)
            put("host", "")
            put("fingerprint", "")
            put("port", port)
            put("secret", session.secret)
            put("pairing_uri", pairingURI)
            put("expires_at", session.expiresAtMs.toString())
        }.toString()

        sendResponse(output, 200, "OK", json)
    }

    private fun handlePairConfirm(
        sock: Socket,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val bodyStr = readBodyString(headers, input)
        try {
            val json = JSONObject(bodyStr)
            val secret = json.optString("secret")
            val remoteName = json.optString("device_name", "Remote Device")
            val remoteIp = sock.inetAddress?.hostAddress ?: ""
            val host = json.optString("host", remoteIp).ifBlank { remoteIp }
            val isTls = json.optBoolean("is_tls", false)
            val port = json.optInt("port", if (isTls) 8443 else 8444)
            val fp = json.optString("fingerprint", "")
            val remoteDevId = json.optString("device_id", UUID.randomUUID().toString())

            if (TvPairingManager.validateLocalSession(secret)) {
                val newAuthToken = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString()
                TvPairingManager.activeLocalSession?.authToken = newAuthToken

                val peer = PeerStatus(
                    id = remoteDevId,
                    name = remoteName,
                    host = host,
                    port = port,
                    isTls = isTls,
                    fingerprint = fp,
                    isPaired = true,
                    isConnected = true,
                    authToken = newAuthToken,
                    presenceState = PresenceState.ONLINE,
                    latencyMs = 15L,
                    lastSeenAt = System.currentTimeMillis()
                )

                pairedDeviceStore.saveDevice(peer)
                TvPairingManager.activeLocalSession = null // Consume one-time session
                TvPairingManager.notifyPairSuccess(peer)

                val devName = TvIdentityManager.getDeviceName()
                val devId = TvIdentityManager.getDeviceId(context)

                val responseJson = JSONObject().apply {
                    put("status", "paired")
                    put("auth_token", newAuthToken)
                    put("device_id", devId)
                    put("device_name", devName)
                    put("fingerprint", "")
                }.toString()

                sendResponse(output, 200, "OK", responseJson)
            } else {
                sendResponse(output, 403, "Forbidden", "{\"error\":\"pairing_failed\",\"message\":\"Invalid or expired secret\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handlePairConfirm", e)
            sendResponse(output, 400, "Bad Request", "{\"error\":\"invalid_json\",\"message\":\"Failed to parse pair confirmation JSON\"}")
        }
    }

    private fun handleUploadHead(headers: Map<String, String>, output: OutputStream) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        val pairedList = pairedDeviceStore.pairedDevices.value
        if (pairedList.isNotEmpty()) {
            val isAuthorized = pairedList.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\",\"message\":\"Device not paired\"}")
                return
            }
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Upload-Offset: 0\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun handleUpload(
        sock: Socket,
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        val pairedList = pairedDeviceStore.pairedDevices.value
        val isAuthorized = if (pairedList.isNotEmpty()) {
            pairedList.any { it.authToken == token }
        } else {
            true
        }

        if (!isAuthorized) {
            sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\",\"message\":\"Device not paired\"}")
            return
        }

        val rawFilename = headers["x-filename"] ?: "incoming_file_${System.currentTimeMillis()}"
        val filename = try {
            java.net.URLDecoder.decode(rawFilename, "UTF-8")
        } catch (_: Exception) {
            rawFilename
        }

        val senderName = headers["x-skydrop-device-name"] ?: headers["x-sender-name"] ?: "Remote Peer"
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

        val streamToRead = if (contentLength >= 0) BoundedInputStream(input, contentLength) else input

        isTransferring = true
        onIncomingTransferStarted?.invoke(filename, senderName, contentLength)
        try {
            val result = storageManager.saveStreamWithResult(filename, streamToRead, contentLength) { written ->
                if (contentLength > 0) {
                    val pct = ((written * 100) / contentLength).toInt().coerceIn(0, 100)
                    onReceiveProgress?.invoke(filename, written, contentLength, pct)
                } else {
                    onReceiveProgress?.invoke(filename, written, -1L, -1)
                }
            }

            if (result.isSuccess) {
                val saveResult = result.getOrThrow()
                val fileId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                val receivedFile = TvReceivedFile(
                    id = fileId,
                    name = filename,
                    sizeBytes = saveResult.bytesWritten,
                    mimeType = saveResult.mimeType,
                    senderName = senderName,
                    receivedAt = now,
                    filePath = saveResult.filePath
                )

                val historyRecord = TvHistoryRecord(
                    id = fileId,
                    timestamp = now,
                    fileName = filename,
                    sizeBytes = saveResult.bytesWritten,
                    peerName = senderName,
                    isSuccessful = true,
                    direction = "inbound",
                    errorMessage = null
                )

                historyStore.addTransfer(historyRecord, receivedFile)
                onFileReceived?.invoke(receivedFile)

                val responseJson = JSONObject().apply {
                    put("status", "success")
                    put("original_name", filename)
                    put("saved_path", saveResult.filePath)
                    put("bytes_written", saveResult.bytesWritten)
                    put("received_at", now.toString())
                }.toString()

                sendResponse(output, 200, "OK", responseJson)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Save failed"
                val fileId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                val historyRecord = TvHistoryRecord(
                    id = fileId,
                    timestamp = now,
                    fileName = filename,
                    sizeBytes = 0L,
                    peerName = senderName,
                    isSuccessful = false,
                    direction = "inbound",
                    errorMessage = err
                )
                historyStore.addTransfer(historyRecord, null)

                sendResponse(output, 500, "Internal Server Error", "{\"error\":\"save_failed\",\"message\":\"$err\"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during upload", e)
            sendResponse(output, 500, "Internal Server Error", "{\"error\":\"internal_error\",\"message\":\"${e.message}\"}")
        } finally {
            isTransferring = false
        }
    }

    private fun handleText(
        headers: Map<String, String>,
        input: InputStream,
        output: OutputStream
    ) {
        val auth = headers["authorization"].orEmpty()
        val token = auth.removePrefix("Bearer ").trim()

        val pairedList = pairedDeviceStore.pairedDevices.value
        if (pairedList.isNotEmpty()) {
            val isAuthorized = pairedList.any { it.authToken == token }
            if (!isAuthorized) {
                sendResponse(output, 401, "Unauthorized", "{\"error\":\"unauthorized\",\"message\":\"Device not paired\"}")
                return
            }
        }

        val bodyStr = readBodyString(headers, input)
        val senderName = headers["x-skydrop-device-name"] ?: headers["x-sender-name"] ?: "Remote Peer"
        val now = System.currentTimeMillis()
        val recordId = UUID.randomUUID().toString()

        val textContent = try {
            val json = JSONObject(bodyStr)
            json.optString("text", bodyStr)
        } catch (_: Exception) {
            bodyStr
        }

        val historyRecord = TvHistoryRecord(
            id = recordId,
            timestamp = now,
            fileName = if (textContent.length > 40) textContent.take(40) + "..." else textContent,
            sizeBytes = textContent.toByteArray(Charsets.UTF_8).size.toLong(),
            peerName = senderName,
            isSuccessful = true,
            direction = "inbound",
            errorMessage = null
        )
        historyStore.addTransfer(historyRecord, null)
        onTextReceived?.invoke(textContent, senderName)

        sendResponse(output, 200, "OK", "{\"status\":\"success\"}")
    }

    private fun handlePresenceLeave(output: OutputStream) {
        sendResponse(output, 200, "OK", "{\"status\":\"ok\"}")
    }

    private fun readBodyString(headers: Map<String, String>, input: InputStream): String {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 4096
        val toRead = contentLength.coerceAtMost(MAX_BODY_BYTES)
        val buf = ByteArray(toRead)
        var totalRead = 0
        while (totalRead < toRead) {
            val count = input.read(buf, totalRead, toRead - totalRead)
            if (count == -1) break
            totalRead += count
        }
        return if (totalRead > 0) String(buf, 0, totalRead, Charsets.UTF_8) else ""
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, statusText: String, jsonBody: String) {
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private class BoundedInputStream(
        private val target: InputStream,
        private val limit: Long
    ) : InputStream() {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val b = target.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val maxToRead = len.toLong().coerceAtMost(limit - bytesRead).toInt()
            val read = target.read(b, off, maxToRead)
            if (read != -1) bytesRead += read
            return read
        }
    }
}
