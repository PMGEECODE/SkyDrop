package com.famage.skydrop_tv_app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.famage.skydrop_tv_app.MainActivity
import com.famage.skydrop_tv_app.R
import com.famage.skydrop_tv_app.discovery.TvDiscoveryManager
import com.famage.skydrop_tv_app.history.TvHistoryStore
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.presence.TvPresenceManager
import com.famage.skydrop_tv_app.security.TvPairedDeviceStore
import com.famage.skydrop_tv_app.settings.TvSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TvReceiveProgress(
    val filename: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percent: Int
)

/**
 * 24/7 Background Receiver Service for Android TV.
 *
 * Keeps the TV receiver, mDNS discovery and presence engine running in the background
 * so users can drop files to the TV anytime, even when the TV app is not in focus
 * or when using other TV apps / home screen.
 */
class TvReceiverService : Service() {

    companion object {
        private const val TAG = "TvReceiverService"
        const val NOTIFICATION_ID_PERSISTENT = TvNotificationHelper.NOTIFICATION_ID_PERSISTENT
        const val NOTIFICATION_ID_INCOMING = TvNotificationHelper.NOTIFICATION_ID_INCOMING

        const val ACTION_START = "com.famage.skydrop.tv.action.START"
        const val ACTION_STOP = "com.famage.skydrop.tv.action.STOP"

        private val _activeReceiveProgress = MutableStateFlow<TvReceiveProgress?>(null)
        val activeReceiveProgress: StateFlow<TvReceiveProgress?> = _activeReceiveProgress.asStateFlow()

        private val _latestAlertMessage = MutableStateFlow<String?>(null)
        val latestAlertMessage: StateFlow<String?> = _latestAlertMessage.asStateFlow()

        var isAppInForeground: Boolean = false

        var activeServer: TvReceiverServer? = null
            private set

        var activePresenceManager: TvPresenceManager? = null
            private set

        var activeDiscoveryManager: TvDiscoveryManager? = null
            private set

        fun startService(context: Context) {
            try {
                val intent = Intent(context, TvReceiverService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "TvReceiverService start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TvReceiverService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, TvReceiverService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                Log.i(TAG, "TvReceiverService stop requested")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop TvReceiverService: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var systemOverlayManager: TvSystemOverlayManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TvReceiverService onCreate")
        TvNotificationHelper.createChannels(this)
        startForeground(NOTIFICATION_ID_PERSISTENT, TvNotificationHelper.buildPersistentNotification(this, 8444))
        acquireMulticastLock()
        systemOverlayManager = TvSystemOverlayManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping TvReceiverService via ACTION_STOP")
            stopReceiver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID_PERSISTENT, TvNotificationHelper.buildPersistentNotification(this, 8444))
        startReceiver()
        return START_STICKY
    }

    private fun startReceiver() {
        if (activeServer != null && activeServer?.isRunning == true) {
            Log.d(TAG, "Receiver already active")
            return
        }

        val settingsStore = TvSettingsStore.getInstance(applicationContext)
        val pairedStore = TvPairedDeviceStore.getInstance(applicationContext)
        val historyStore = TvHistoryStore.getInstance(applicationContext)
        val port = settingsStore.listeningPort.value

        try {
            val server = TvReceiverServer(
                context = applicationContext,
                port = port,
                pairedDeviceStore = pairedStore,
                historyStore = historyStore
            ).apply {
                onIncomingTransferStarted = { filename, senderName, totalBytes ->
                    val alertMsg = "Incoming file: $filename from $senderName"
                    _latestAlertMessage.value = alertMsg
                    TvNotificationHelper.showTvToast(applicationContext, "⬇ SkyDrop: $alertMsg")
                    TvNotificationHelper.showIncomingFileNotification(applicationContext, filename, senderName, totalBytes, 0)
                    if (!isAppInForeground) {
                        systemOverlayManager?.updateProgress(TvReceiveProgress(filename, 0L, totalBytes, 0))
                    }
                }
                onReceiveProgress = { filename, bytesRead, totalBytes, percent ->
                    val progress = TvReceiveProgress(filename, bytesRead, totalBytes, percent)
                    _activeReceiveProgress.value = progress
                    acquireTransientWakeLock(5000L)
                    TvNotificationHelper.updateTransferNotification(applicationContext, filename, totalBytes, percent)
                    if (!isAppInForeground) {
                        systemOverlayManager?.updateProgress(progress)
                    }
                }
                onFileReceived = { file ->
                    _activeReceiveProgress.value = null
                    val msg = "Received: ${file.name} from ${file.senderName}"
                    _latestAlertMessage.value = msg
                    TvNotificationHelper.cancelIncomingNotification(applicationContext)
                    TvNotificationHelper.showTvToast(applicationContext, "✓ SkyDrop: Received ${file.name}")
                    TvNotificationHelper.showFileReceivedNotification(applicationContext, file)
                    if (!isAppInForeground) {
                        systemOverlayManager?.showCompletion("✓ Received ${file.name}")
                    }
                }
                onTextReceived = { text, sender ->
                    val preview = if (text.length > 25) text.take(25) + "..." else text
                    val msg = "Received from $sender: \"$preview\""
                    _latestAlertMessage.value = msg
                    TvNotificationHelper.showTvToast(applicationContext, "SkyDrop: $msg")
                    TvNotificationHelper.showTextReceivedNotification(applicationContext, sender, preview)
                }
            }

            val actualPort = server.start()
            activeServer = server

            val discovery = TvDiscoveryManager(applicationContext).apply {
                start(actualPort)
            }
            activeDiscoveryManager = discovery

            val presence = TvPresenceManager(applicationContext, pairedStore).apply {
                start()
            }
            activePresenceManager = presence

            serviceScope.launch {
                discovery.discoveredPeers.collect { list ->
                    list.forEach { peer ->
                        presence.onMdnsPeerDiscovered(peer)
                    }
                }
            }

            // Update persistent notification with actual port
            val notification = TvNotificationHelper.buildPersistentNotification(this, actualPort)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID_PERSISTENT, notification)

            Log.i(TAG, "TvReceiverService fully active on port $actualPort")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TV receiver in service", e)
        }
    }

    private fun stopReceiver() {
        try {
            activeServer?.stop()
            activeServer = null
            activeDiscoveryManager?.stop()
            activeDiscoveryManager = null
            activePresenceManager?.stop()
            activePresenceManager = null
            releaseLocks()
            Log.i(TAG, "TvReceiverService stopped internal servers")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping receiver: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("SkyDropTv:MulticastLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "MulticastLock acquired for 24/7 local TV discovery")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun acquireTransientWakeLock(durationMs: Long = 5000L) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SkyDropTv:TransferLock")
                }
                wakeLock?.acquire(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transient WakeLock error: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "TvReceiverService onDestroy")
        systemOverlayManager?.destroy()
        systemOverlayManager = null
        stopReceiver()
        serviceScope.cancel()
    }
}
