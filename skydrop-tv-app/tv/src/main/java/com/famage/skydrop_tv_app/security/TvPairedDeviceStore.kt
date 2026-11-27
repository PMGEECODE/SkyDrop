package com.famage.skydrop_tv_app.security

import android.content.Context
import android.content.SharedPreferences
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TvPairedDeviceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pairedDevices = MutableStateFlow<List<PeerStatus>>(emptyList())
    val pairedDevices: StateFlow<List<PeerStatus>> = _pairedDevices.asStateFlow()

    companion object {
        private const val PREFS_NAME = "skydrop_tv_paired_peers"
        private const val KEY_PEERS_JSON = "peers_json"

        @Volatile
        private var INSTANCE: TvPairedDeviceStore? = null

        fun getInstance(context: Context): TvPairedDeviceStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TvPairedDeviceStore(context).also { INSTANCE = it }
            }
    }

    init {
        loadDevices()
    }

    @Synchronized
    fun saveDevice(peer: PeerStatus) {
        loadDevices()
        val current = _pairedDevices.value.filter { it.id != peer.id && !(it.host == peer.host && it.port == peer.port) }.toMutableList()
        current.add(peer)
        persist(current)
    }

    @Synchronized
    fun removeDevice(id: String) {
        loadDevices()
        val filtered = _pairedDevices.value.filter { it.id != id }
        persist(filtered)
    }

    @Synchronized
    fun updatePresence(id: String, state: PresenceState, latency: Long = 0L) {
        loadDevices()
        val current = _pairedDevices.value.map { peer ->
            if (peer.id == id) {
                peer.copy(presenceState = state, latencyMs = latency, lastSeenAt = System.currentTimeMillis())
            } else {
                peer
            }
        }
        persist(current)
    }

    private fun loadDevices() {
        val rawJson = prefs.getString(KEY_PEERS_JSON, null)
        if (rawJson.isNullOrBlank()) {
            _pairedDevices.value = emptyList()
            return
        }

        val list = mutableListOf<PeerStatus>()
        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PeerStatus(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        host = obj.getString("host"),
                        port = obj.optInt("port", 8443),
                        isTls = obj.optBoolean("is_tls", true),
                        fingerprint = if (obj.has("fingerprint") && !obj.isNull("fingerprint")) obj.getString("fingerprint") else null,
                        isPaired = true,
                        isConnected = obj.optBoolean("is_connected", true),
                        authToken = if (obj.has("auth_token") && !obj.isNull("auth_token")) obj.getString("auth_token") else null,
                        presenceState = try {
                            PresenceState.valueOf(obj.optString("presence_state", "ONLINE"))
                        } catch (_: Exception) {
                            PresenceState.ONLINE
                        },
                        latencyMs = obj.optLong("latency_ms", 15L),
                        lastSeenAt = obj.optLong("last_seen_at", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
            // Keep current on parse error
        }
        _pairedDevices.value = list
    }

    private fun persist(list: List<PeerStatus>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("host", item.host)
                put("port", item.port)
                put("is_tls", item.isTls)
                put("fingerprint", item.fingerprint ?: "")
                put("auth_token", item.authToken ?: "")
                put("presence_state", item.presenceState.name)
                put("latency_ms", item.latencyMs)
                put("last_seen_at", item.lastSeenAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PEERS_JSON, array.toString()).apply()
        _pairedDevices.value = list
    }
}
