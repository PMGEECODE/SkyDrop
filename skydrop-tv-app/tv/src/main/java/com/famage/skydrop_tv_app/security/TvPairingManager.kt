package com.famage.skydrop_tv_app.security

import android.net.Uri
import android.util.Log
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.IncomingPairRequest
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object TvPairingManager {

    private const val TAG = "TvPairingManager"

    data class LocalPairingSession(
        val secret: String,
        val expiresAtMs: Long,
        var authToken: String? = null,
        var failedAttempts: Int = 0
    )

    @Volatile
    var activeLocalSession: LocalPairingSession? = null

    val pendingIncomingRequests = ConcurrentHashMap<String, IncomingPairRequest>()
    private val _activeIncomingRequests = MutableStateFlow<List<IncomingPairRequest>>(emptyList())
    val activeIncomingRequests: StateFlow<List<IncomingPairRequest>> = _activeIncomingRequests.asStateFlow()

    private val _pairSuccessEvents = MutableSharedFlow<PeerStatus>(extraBufferCapacity = 8)
    val pairSuccessEvents: SharedFlow<PeerStatus> = _pairSuccessEvents.asSharedFlow()

    fun addIncomingRequest(req: IncomingPairRequest) {
        pendingIncomingRequests[req.requestId] = req
        updateIncomingFlow()
    }

    fun getPendingPairRequests(): List<IncomingPairRequest> {
        val now = System.currentTimeMillis()
        return pendingIncomingRequests.values.filter { it.status == "pending" && it.expiresAtMs > now }
    }

    fun acceptIncomingRequest(requestId: String): Pair<IncomingPairRequest, LocalPairingSession>? {
        val req = pendingIncomingRequests[requestId] ?: return null
        if (System.currentTimeMillis() > req.expiresAtMs) {
            req.status = "expired"
            updateIncomingFlow()
            return null
        }
        req.status = "accepted"
        val session = generateLocalSession()
        updateIncomingFlow()
        return Pair(req, session)
    }

    fun declineIncomingRequest(requestId: String): IncomingPairRequest? {
        val req = pendingIncomingRequests[requestId] ?: return null
        req.status = "declined"
        updateIncomingFlow()
        return req
    }

    fun cancelIncomingRequest(requestId: String): IncomingPairRequest? {
        val req = pendingIncomingRequests[requestId] ?: return null
        req.status = "cancelled"
        updateIncomingFlow()
        return req
    }

    fun updateIncomingFlow() {
        _activeIncomingRequests.value = getPendingPairRequests()
    }

    fun generateLocalSession(): LocalPairingSession {
        val random = SecureRandom()
        val secret = String.format("%06d", random.nextInt(1000000))
        val expiresAt = System.currentTimeMillis() + 300_000L // 5 minutes validity
        val session = LocalPairingSession(secret, expiresAt)
        activeLocalSession = session
        return session
    }

    fun getActivePin(): String {
        val session = activeLocalSession
        if (session != null && System.currentTimeMillis() < session.expiresAtMs) {
            return session.secret
        }
        return generateLocalSession().secret
    }

    fun validateLocalSession(secret: String): Boolean {
        val session = activeLocalSession ?: return false
        if (System.currentTimeMillis() > session.expiresAtMs) {
            activeLocalSession = null
            return false
        }
        if (session.failedAttempts >= 5) {
            activeLocalSession = null
            return false
        }
        val matches = session.secret == secret
        if (!matches) {
            session.failedAttempts++
            if (session.failedAttempts >= 5) {
                activeLocalSession = null
            }
        }
        return matches
    }

    fun notifyPairSuccess(peer: PeerStatus) {
        _pairSuccessEvents.tryEmit(peer)
    }

    fun buildPairingQrUri(
        host: String,
        port: Int = 8444,
        deviceName: String,
        deviceId: String,
        secret: String
    ): String {
        val encodedName = Uri.encode(deviceName)
        return "skydrop://pair?device=$encodedName&name=$encodedName&host=$host&port=$port&tls=0&id=$deviceId&secret=$secret&fingerprint="
    }

    suspend fun requestPairing(
        host: String,
        port: Int,
        isTls: Boolean,
        localDeviceName: String,
        localDeviceId: String,
        onStatusUpdate: ((status: String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val useTls = isTls || (port == 8443)
            val scheme = if (useTls) "https" else "http"
            val url = "$scheme://$host:$port/pair/request"

            val allCiphersSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build()

            val client = if (useTls) createInsecureSslClient() else OkHttpClient.Builder()
                .connectionSpecs(listOf(allCiphersSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val reqId = java.util.UUID.randomUUID().toString()
            val tvIp = TvIdentityManager.getLocalIpAddress()
            val jsonBody = JSONObject().apply {
                put("request_id", reqId)
                put("device_name", localDeviceName)
                put("device_id", localDeviceId)
                put("device_type", "tv")
                put("host", tvIp)
                put("port", 8444)
                put("is_tls", false)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .header("X-SkyDrop-Device-ID", localDeviceId)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            val resp = client.newCall(request).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    val err = r.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Pairing request failed (HTTP ${r.code}): $err"))
                }
            }

            onStatusUpdate?.invoke("Waiting for approval on remote device...")

            // Poll status up to 45 seconds
            val statusUrl = "$scheme://$host:$port/pair/request/status?request_id=$reqId"
            val deadline = System.currentTimeMillis() + 45000L

            while (System.currentTimeMillis() < deadline) {
                delay(1500L)
                val stReq = Request.Builder().url(statusUrl).build()
                try {
                    client.newCall(stReq).execute().use { stResp ->
                        if (stResp.isSuccessful) {
                            val stBody = stResp.body?.string().orEmpty()
                            val stJson = JSONObject(stBody)
                            val status = stJson.optString("status")
                            when (status) {
                                "accepted" -> return@withContext Result.success(reqId)
                                "declined" -> return@withContext Result.failure(IOException("Pairing request was declined by the receiving device"))
                                "cancelled", "expired" -> return@withContext Result.failure(IOException("Pairing request was $status"))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            Result.failure(IOException("Pairing request timed out waiting for approval"))
        } catch (e: Exception) {
            Log.e(TAG, "Request pairing error", e)
            Result.failure(e)
        }
    }

    suspend fun pairWithDevice(
        host: String,
        port: Int,
        secret: String,
        localDeviceName: String,
        localDeviceId: String,
        isTls: Boolean = (port == 8443)
    ): Result<PeerStatus> = withContext(Dispatchers.IO) {
        try {
            val scheme = if (isTls) "https" else "http"
            val url = "$scheme://$host:$port/pair/confirm"

            val allCiphersSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build()

            val client = if (isTls) createInsecureSslClient() else OkHttpClient.Builder()
                .connectionSpecs(listOf(allCiphersSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val jsonBody = JSONObject().apply {
                put("device_name", localDeviceName)
                put("fingerprint", "")
                put("secret", secret.trim())
                put("host", "192.168.74.3")
                put("port", 8444)
                put("is_tls", false)
                put("device_type", "tv")
                put("device_id", localDeviceId)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .header("X-SkyDrop-Device-ID", localDeviceId)
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    return@withContext Result.failure(IOException("Pairing failed (HTTP ${response.code}): $err"))
                }

                val bodyStr = response.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val authToken = json.optString("auth_token", "")
                val deviceId = json.optString("device_id", "$host:$port")
                val remoteName = json.optString("device_name", if (port == 8443) "Desktop Peer" else "Mobile Peer")
                val remoteFingerprint = json.optString("fingerprint", "")

                val peer = PeerStatus(
                    id = deviceId,
                    name = remoteName,
                    host = host,
                    port = port,
                    isTls = isTls,
                    fingerprint = remoteFingerprint,
                    isPaired = true,
                    isConnected = true,
                    authToken = authToken,
                    presenceState = PresenceState.ONLINE,
                    latencyMs = 15L,
                    lastSeenAt = System.currentTimeMillis()
                )

                notifyPairSuccess(peer)
                Result.success(peer)
            }
        } catch (e: Exception) {
            if (isTls && (e is IOException || e is java.net.SocketTimeoutException)) {
                Log.w(TAG, "HTTPS pair confirmation to $host:$port failed, retrying over HTTP...")
                return@withContext pairWithDevice(
                    host = host,
                    port = port,
                    secret = secret,
                    localDeviceName = localDeviceName,
                    localDeviceId = localDeviceId,
                    isTls = false
                )
            }
            Log.e(TAG, "Pairing network error for $host:$port", e)
            Result.failure(e)
        }
    }

    private fun createInsecureSslClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        val allCiphersSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .allEnabledTlsVersions()
            .allEnabledCipherSuites()
            .build()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(allCiphersSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
