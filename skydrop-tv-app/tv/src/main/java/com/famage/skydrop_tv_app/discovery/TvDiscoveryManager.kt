package com.famage.skydrop_tv_app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Robust Network Service Discovery manager using Android's NsdManager on Android TV.
 *
 * Handles:
 * - MulticastLock acquisition and release
 * - Service discovery for _filedrop._tcp and _skydrop._tcp
 * - TV mDNS Service registration so Mobile & Desktop discover this TV
 * - Serialized resolve queue with watchdog timeouts
 * - Self-device filtering
 */
class TvDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredPeers = MutableStateFlow<List<PeerStatus>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerStatus>> = _discoveredPeers.asStateFlow()

    private val discoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isDiscovering = false
    private var isRegistered = false

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resolveTimeoutRunnable: Runnable? = null

    companion object {
        private const val TAG = "TvDiscoveryManager"
        val SUPPORTED_SERVICE_TYPES = listOf("_filedrop._tcp", "_skydrop._tcp")
        private const val RESOLVE_TIMEOUT_MS = 4000L
        private const val MAX_RESOLVE_QUEUE_SIZE = 30
    }

    @Synchronized
    fun start(listeningPort: Int = 8444) {
        startDiscovery()
        registerTvService(listeningPort)
    }

    @Synchronized
    fun stop() {
        stopDiscovery()
        unregisterTvService()
    }

    @Synchronized
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        acquireMulticastLock()
        isDiscovering = true

        for (serviceType in SUPPORTED_SERVICE_TYPES) {
            val listener = createDiscoveryListener(serviceType)
            discoveryListeners[serviceType] = listener
            try {
                Log.d(TAG, "Starting NsdManager discovery for: $serviceType")
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service discovery for $serviceType", e)
                discoveryListeners.remove(serviceType)
            }
        }

        if (discoveryListeners.isEmpty()) {
            isDiscovering = false
            releaseMulticastLock()
        }
    }

    @Synchronized
    fun stopDiscovery() {
        if (!isDiscovering) return

        for ((_, listener) in discoveryListeners) {
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery listener", e)
            }
        }
        discoveryListeners.clear()

        resolveQueue.clear()
        resolveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        resolveTimeoutRunnable = null
        isResolving = false

        isDiscovering = false
        releaseMulticastLock()
    }

    @Synchronized
    fun registerTvService(port: Int) {
        if (isRegistered || nsdManager == null) return

        try {
            val deviceName = TvIdentityManager.getDeviceName()
            val deviceId = TvIdentityManager.getDeviceId(context)

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_filedrop._tcp"
                setPort(port)
                setAttribute("tls", "0")
                setAttribute("id", deviceId)
                setAttribute("device_id", deviceId)
                setAttribute("os", "android-tv")
                setAttribute("version", "1.0.0")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                    Log.i(TAG, "TV mDNS service registered: ${registeredService.serviceName} on port $port")
                    isRegistered = true
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "TV mDNS registration failed: errorCode $errorCode")
                    isRegistered = false
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    Log.i(TAG, "TV mDNS service unregistered")
                    isRegistered = false
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "TV mDNS unregistration failed: errorCode $errorCode")
                }
            }

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error registering TV mDNS service: ${e.message}")
        }
    }

    @Synchronized
    fun unregisterTvService() {
        if (!isRegistered || registrationListener == null) return
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering TV mDNS service: ${e.message}")
        }
        registrationListener = null
        isRegistered = false
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started for: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val cleanType = service.serviceType.trim('.', ' ')
                val expectedTypes = SUPPORTED_SERVICE_TYPES.map { it.trim('.', ' ') }
                if (expectedTypes.none { cleanType.contains(it, ignoreCase = true) }) {
                    return
                }
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                removePeer(service.serviceName)
            }

            override fun onDiscoveryStopped(stoppedServiceType: String) {
                Log.d(TAG, "Discovery stopped for: $stoppedServiceType")
            }

            override fun onStartDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed for $failedServiceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(failedServiceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed for $failedServiceType: $errorCode")
            }
        }
    }

    @Synchronized
    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        if (resolveQueue.size >= MAX_RESOLVE_QUEUE_SIZE) {
            resolveQueue.poll()
        }
        if (resolveQueue.none { it.serviceName == serviceInfo.serviceName }) {
            resolveQueue.add(serviceInfo)
        }
        processNextResolve()
    }

    @Synchronized
    private fun processNextResolve() {
        if (isResolving || resolveQueue.isEmpty() || nsdManager == null) return

        val nextService = resolveQueue.poll() ?: return
        isResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                finishResolve()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                    handleResolvedService(resolved)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling resolved service: ${resolved.serviceName}", e)
                } finally {
                    finishResolve()
                }
            }
        }

        resolveTimeoutRunnable = Runnable {
            synchronized(this@TvDiscoveryManager) {
                if (isResolving) {
                    isResolving = false
                    processNextResolve()
                }
            }
        }
        resolveTimeoutRunnable?.let { mainHandler.postDelayed(it, RESOLVE_TIMEOUT_MS) }

        try {
            nsdManager.resolveService(nextService, resolveListener)
        } catch (e: Exception) {
            finishResolve()
        }
    }

    @Synchronized
    private fun finishResolve() {
        resolveTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        resolveTimeoutRunnable = null
        isResolving = false
        processNextResolve()
    }

    private fun handleResolvedService(resolved: NsdServiceInfo) {
        val host = resolved.host?.hostAddress ?: return
        val port = resolved.port
        val name = resolved.serviceName

        val localIp = TvIdentityManager.getLocalIpAddress()
        val localDevId = TvIdentityManager.getDeviceId(context)

        // Filter self
        if (host == localIp || host == "127.0.0.1") return

        var isTls = (port == 8443)
        var fingerprint: String? = null
        var peerIdAttr: String? = null

        val attributes = resolved.attributes
        if (attributes != null) {
            attributes["tls"]?.let {
                val tlsStr = String(it)
                isTls = tlsStr == "true" || tlsStr == "1"
            }
            attributes["fingerprint"]?.let { fingerprint = String(it) }
            peerIdAttr = attributes["id"]?.let { String(it) }
                ?: attributes["device_id"]?.let { String(it) }
        }

        if (peerIdAttr == localDevId) return

        val peer = PeerStatus(
            id = peerIdAttr?.takeIf { it.isNotBlank() } ?: "$host:$port",
            name = name,
            host = host,
            port = port,
            isTls = isTls,
            fingerprint = fingerprint,
            isPaired = false,
            presenceState = PresenceState.ONLINE,
            lastSeenAt = System.currentTimeMillis()
        )

        addOrUpdatePeer(peer)
    }

    @Synchronized
    fun addOrUpdatePeer(peer: PeerStatus) {
        val current = _discoveredPeers.value.toMutableList()
        val index = current.indexOfFirst { it.id == peer.id || (it.host == peer.host && it.port == peer.port) }
        if (index >= 0) {
            current[index] = peer
        } else {
            current.add(peer)
        }
        _discoveredPeers.value = current
    }

    @Synchronized
    private fun removePeer(serviceName: String) {
        _discoveredPeers.value = _discoveredPeers.value.filter { it.name != serviceName }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("SkyDropTvMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
    }
}
