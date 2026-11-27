package com.famage.skydrop_tv_app.model

enum class PresenceState {
    ONLINE,
    BUSY,
    DEGRADED,
    OFFLINE,
    ERROR,
    UNKNOWN
}

/**
 * Peer status matching Android app model exactly.
 */
data class PeerStatus(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 8443,
    val isTls: Boolean = true,
    val fingerprint: String? = null,
    val isPaired: Boolean = false,
    val isConnected: Boolean = false,
    val authToken: String? = null,
    val presenceState: PresenceState = PresenceState.UNKNOWN,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val lastSeenAt: Long = 0L
) {
    val canSend: Boolean
        get() = isPaired && (presenceState == PresenceState.ONLINE || presenceState == PresenceState.BUSY)
    
    val isOnline: Boolean
        get() = presenceState == PresenceState.ONLINE
}

// Backward compatibility alias for any existing reference
typealias TvPairedPeer = PeerStatus

data class TvReceivedFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val senderName: String,
    val receivedAt: Long,
    val filePath: String
)

data class TvHistoryRecord(
    val id: String,
    val timestamp: Long,
    val fileName: String,
    val sizeBytes: Long,
    val peerName: String,
    val isSuccessful: Boolean,
    val direction: String = "inbound",
    val errorMessage: String? = null
)

data class IncomingPairRequest(
    val requestId: String,
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val host: String,
    val port: Int,
    val isTls: Boolean,
    val fingerprint: String,
    val expiresAtMs: Long,
    var status: String = "pending"
)
