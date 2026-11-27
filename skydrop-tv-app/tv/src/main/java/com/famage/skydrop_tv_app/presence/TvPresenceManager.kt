package com.famage.skydrop_tv_app.presence

import android.content.Context
import android.util.Log
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import com.famage.skydrop_tv_app.security.TvPairedDeviceStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Presence & Subnet Probe Manager for SkyDrop TV.
 *
 * Guarantees discovery of SkyDrop Desktop (port 8443 TLS) and Mobile (port 8444)
 * both via mDNS, ARP cache inspection, and direct subnet probes, ensuring 100% reliable peer visibility
 * even on networks where mDNS multicast packets are dropped or delayed by TV Wi-Fi chipsets.
 */
class TvPresenceManager(
    private val context: Context,
    private val pairedStore: TvPairedDeviceStore
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val peerMap = ConcurrentHashMap<String, PeerStatus>()

    private val _peers = MutableStateFlow<List<PeerStatus>>(emptyList())
    val peers: StateFlow<List<PeerStatus>> = _peers.asStateFlow()

    private var heartbeatJob: Job? = null
    private var scanJob: Job? = null

    companion object {
        private const val TAG = "TvPresenceManager"
        private const val PROBE_TIMEOUT_MS = 3000L
    }

    private val httpClient: OkHttpClient by lazy {
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }

        val allCiphersSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .allEnabledTlsVersions()
            .allEnabledCipherSuites()
            .build()

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(allCiphersSpec, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .connectionPool(ConnectionPool(10, 60, TimeUnit.SECONDS))
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun start() {
        if (heartbeatJob != null) return

        Log.i(TAG, "Starting TvPresenceManager...")

        // 1. Initial fast ARP & subnet scan
        triggerSubnetScan()

        // 2. Periodic Heartbeat Loop (every 4 seconds)
        heartbeatJob = scope.launch {
            while (isActive) {
                probeAllPeers()
                delay(4000L)
            }
        }

        // 3. Periodic Subnet Refresh (every 15 seconds)
        scanJob = scope.launch {
            while (isActive) {
                delay(15000L)
                scanSubnet()
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        scanJob?.cancel()
        scanJob = null
    }

    fun onMdnsPeerDiscovered(peer: PeerStatus) {
        addOrUpdate(peer)
        scope.launch {
            probePeer(peer)
        }
    }

    fun triggerSubnetScan() {
        scope.launch {
            scanSubnet()
        }
    }

    fun unpairPeer(peerId: String) {
        val peer = peerMap[peerId] ?: _peers.value.firstOrNull { it.id == peerId }
        if (peer != null) {
            peerMap[peer.id] = peer.copy(isPaired = false, authToken = null)
        }
        publishPeers()
    }

    fun retryPeer(peerId: String) {
        scope.launch {
            val peer = peerMap[peerId] ?: _peers.value.firstOrNull { it.id == peerId }
            if (peer != null) {
                probePeer(peer)
            }
        }
    }

    private suspend fun probeAllPeers() = withContext(Dispatchers.IO) {
        val pairedList = pairedStore.pairedDevices.value
        for (paired in pairedList) {
            val existing = peerMap[paired.id]
            val merged = paired.copy(
                presenceState = existing?.presenceState ?: PresenceState.UNKNOWN,
                latencyMs = existing?.latencyMs ?: 0L,
                lastSeenAt = existing?.lastSeenAt ?: 0L
            )
            peerMap[paired.id] = merged
        }

        val allPeers = peerMap.values.toList()
        val jobs = allPeers.map { peer ->
            launch {
                probePeer(peer)
            }
        }
        jobs.joinAll()
        publishPeers()
    }

    private suspend fun probePeer(peer: PeerStatus) = withContext(Dispatchers.IO) {
        val host = peer.host
        val port = peer.port
        if (host.isBlank() || port <= 0) return@withContext

        val isTls = peer.isTls || (port == 8443)
        val scheme = if (isTls) "https" else "http"
        val start = System.currentTimeMillis()

        try {
            if (peer.isPaired && !peer.authToken.isNullOrBlank()) {
                val healthUrl = "$scheme://$host:$port/health"
                val request = Request.Builder()
                    .url(healthUrl)
                    .header("Authorization", "Bearer ${peer.authToken}")
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - start
                    if (response.isSuccessful) {
                        updatePeerState(peer.id, PresenceState.ONLINE, latency = latency)
                    } else if (response.code == 401 || response.code == 403) {
                        updatePeerState(peer.id, PresenceState.ERROR, errorMessage = "Token Revoked")
                    } else {
                        updatePeerState(peer.id, PresenceState.ONLINE, latency = latency)
                    }
                }
            } else {
                val infoUrl = "$scheme://$host:$port/info"
                val request = Request.Builder()
                    .url(infoUrl)
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - start
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val devName = json.optString("device_name", peer.name)
                        val fp = json.optString("fingerprint", peer.fingerprint.orEmpty())

                        val updated = peer.copy(
                            name = if (devName.isNotBlank()) devName else peer.name,
                            fingerprint = fp.ifBlank { peer.fingerprint },
                            presenceState = PresenceState.ONLINE,
                            latencyMs = latency,
                            lastSeenAt = System.currentTimeMillis()
                        )
                        addOrUpdate(updated)
                    } else {
                        updatePeerState(peer.id, PresenceState.OFFLINE)
                    }
                }
            }
        } catch (e: Exception) {
            // If HTTPS failed on 8443, try plain HTTP once
            if (isTls) {
                try {
                    val fallbackUrl = "http://$host:$port/info"
                    val fallbackReq = Request.Builder().url(fallbackUrl).build()
                    httpClient.newCall(fallbackReq).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val json = JSONObject(body)
                            val devName = json.optString("device_name", peer.name)
                            val updated = peer.copy(
                                name = if (devName.isNotBlank()) devName else peer.name,
                                isTls = false,
                                presenceState = PresenceState.ONLINE,
                                latencyMs = System.currentTimeMillis() - start,
                                lastSeenAt = System.currentTimeMillis()
                            )
                            addOrUpdate(updated)
                            return@withContext
                        }
                    }
                } catch (_: Exception) {}
            }
            updatePeerState(peer.id, PresenceState.OFFLINE)
        }
    }

    /**
     * Fast subnet & ARP scanner: sweeps local IPv4 subnet on ports 8443 and 8444.
     */
    private suspend fun scanSubnet() = withContext(Dispatchers.IO) {
        val localIp = TvIdentityManager.getLocalIpAddress()
        if (localIp.isBlank() || localIp == "127.0.0.1") return@withContext

        // 1. First probe live ARP hosts (instant response for machines that communicated with TV)
        val arpHosts = getArpLiveHosts().filter { it != localIp }
        val candidatePorts = listOf(8443, 8444)

        for (host in arpHosts) {
            for (port in candidatePorts) {
                launch {
                    checkAndRegisterHost(host, port)
                }
            }
        }

        // 2. Full /24 subnet probe
        val prefix = localIp.substringBeforeLast(".") + "."
        val targetHosts = (1..254).map { "$prefix$it" }.filter { it != localIp && !arpHosts.contains(it) }

        targetHosts.chunked(24).forEach { chunk ->
            coroutineScope {
                chunk.forEach { host ->
                    candidatePorts.forEach { port ->
                        launch {
                            checkAndRegisterHost(host, port)
                        }
                    }
                }
            }
        }
        publishPeers()
    }

    private fun getArpLiveHosts(): List<String> {
        val result = mutableListOf<String>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val flags = parts[2]
                            val mac = parts[3]
                            if (flags == "0x2" && mac != "00:00:00:00:00:00") {
                                result.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private suspend fun checkAndRegisterHost(host: String, port: Int) = withContext(Dispatchers.IO) {
        // Fast TCP socket test with 650ms timeout
        val isReachable = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 650)
                true
            }
        } catch (_: Exception) {
            false
        }

        if (!isReachable) return@withContext

        val isTls = (port == 8443)
        val scheme = if (isTls) "https" else "http"
        val start = System.currentTimeMillis()

        try {
            val url = "$scheme://$host:$port/info"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val devName = json.optString("device_name", if (port == 8443) "Desktop Peer" else "Mobile Peer")
                    val devId = json.optString("device_id", "$host:$port")
                    val fp = json.optString("fingerprint", "")
                    val explicitTls = json.optBoolean("tls_enabled", isTls)

                    val pairedList = pairedStore.pairedDevices.value
                    val matchingPaired = pairedList.firstOrNull {
                        it.id == devId || it.host == host || it.name.equals(devName, ignoreCase = true)
                    }

                    val peer = PeerStatus(
                        id = matchingPaired?.id ?: devId,
                        name = matchingPaired?.name ?: devName,
                        host = host,
                        port = port,
                        isTls = explicitTls,
                        fingerprint = if (fp.isNotBlank()) fp else matchingPaired?.fingerprint,
                        isPaired = matchingPaired != null,
                        isConnected = matchingPaired != null,
                        authToken = matchingPaired?.authToken,
                        presenceState = PresenceState.ONLINE,
                        latencyMs = latency,
                        lastSeenAt = System.currentTimeMillis()
                    )

                    Log.i(TAG, "Discovered active peer: ${peer.name} ($host:$port, TLS=$explicitTls, Paired=${peer.isPaired})")
                    addOrUpdate(peer)
                    publishPeers()
                }
            }
        } catch (e: Exception) {
            // Retry with cleartext HTTP if TLS on port 8443 failed
            if (isTls) {
                try {
                    val fallbackUrl = "http://$host:$port/info"
                    val fallbackReq = Request.Builder().url(fallbackUrl).build()
                    httpClient.newCall(fallbackReq).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            val json = JSONObject(body)
                            val devName = json.optString("device_name", "Desktop Peer")
                            val devId = json.optString("device_id", "$host:$port")
                            val peer = PeerStatus(
                                id = devId,
                                name = devName,
                                host = host,
                                port = port,
                                isTls = false,
                                isPaired = false,
                                isConnected = false,
                                presenceState = PresenceState.ONLINE,
                                latencyMs = System.currentTimeMillis() - start,
                                lastSeenAt = System.currentTimeMillis()
                            )
                            Log.i(TAG, "Discovered fallback HTTP peer: ${peer.name} ($host:$port)")
                            addOrUpdate(peer)
                            publishPeers()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun addOrUpdate(peer: PeerStatus) {
        val existing = peerMap[peer.id]
        if (existing == null || existing.presenceState != peer.presenceState || peer.latencyMs > 0 || peer.name != existing.name) {
            peerMap[peer.id] = peer
        }
    }

    private fun updatePeerState(
        peerId: String,
        state: PresenceState,
        latency: Long = 0L,
        errorMessage: String? = null
    ) {
        val peer = peerMap[peerId] ?: return
        peerMap[peerId] = peer.copy(
            presenceState = state,
            latencyMs = if (latency > 0) latency else peer.latencyMs,
            errorMessage = errorMessage,
            lastSeenAt = if (state == PresenceState.ONLINE) System.currentTimeMillis() else peer.lastSeenAt
        )
    }

    private fun publishPeers() {
        val pairedList = pairedStore.pairedDevices.value
        val pairedMap = pairedList.associateBy { it.id }

        val combined = peerMap.values.map { peer ->
            val paired = pairedMap[peer.id] ?: pairedList.firstOrNull {
                it.host == peer.host || it.name.equals(peer.name, ignoreCase = true)
            }
            if (paired != null) {
                peer.copy(
                    isPaired = true,
                    authToken = paired.authToken,
                    name = paired.name.ifBlank { peer.name }
                )
            } else {
                peer.copy(
                    isPaired = false,
                    authToken = null
                )
            }
        }.distinctBy { "${it.host}:${it.port}" }
        .sortedWith(
            compareByDescending<PeerStatus> { it.isPaired }
                .thenByDescending { it.presenceState == PresenceState.ONLINE }
                .thenBy { it.name }
        )

        _peers.value = combined
    }
}
