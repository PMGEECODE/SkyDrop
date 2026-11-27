package com.famage.skydrop_tv_app.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.history.TvHistoryStore
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.TvStorageOverview
import com.famage.skydrop_tv_app.model.TvStorageSpace
import com.famage.skydrop_tv_app.presence.TvPresenceManager
import com.famage.skydrop_tv_app.receiver.TvReceiverService
import com.famage.skydrop_tv_app.receiver.TvStorageStatsHelper
import com.famage.skydrop_tv_app.security.TvPairedDeviceStore
import com.famage.skydrop_tv_app.security.TvPairingManager
import com.famage.skydrop_tv_app.settings.TvSettingsStore
import com.famage.skydrop_tv_app.ui.components.TvHeader
import com.famage.skydrop_tv_app.ui.components.TvIncomingPairDialog
import com.famage.skydrop_tv_app.ui.components.TvManualConnectDialog
import com.famage.skydrop_tv_app.ui.components.TvQrDialog
import com.famage.skydrop_tv_app.ui.components.TvSidebar
import com.famage.skydrop_tv_app.ui.components.TvTransferProgressOverlay
import com.famage.skydrop_tv_app.ui.components.TvUpdateDialog
import com.famage.skydrop_tv_app.ui.navigation.TvNavItem
import com.famage.skydrop_tv_app.updater.TvUpdateDownloadProgress
import com.famage.skydrop_tv_app.updater.TvUpdateInfo
import com.famage.skydrop_tv_app.updater.TvUpdateService
import kotlinx.coroutines.launch
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Root TV Screen hosting the floating sidebar and routing strictly to corresponding screens.
 * Uses 24/7 background TvReceiverService for uninterrupted file reception even when not in focus.
 */
@Composable
fun TvMainScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsStore = remember { TvSettingsStore.getInstance(context) }
    val pairedStore = remember { TvPairedDeviceStore.getInstance(context) }
    val historyStore = remember { TvHistoryStore.getInstance(context) }

    val isReceiverActive by settingsStore.isReceiverActive.collectAsState()
    val listeningPort by settingsStore.listeningPort.collectAsState()
    val saveLocation by settingsStore.saveLocation.collectAsState()
    val themePreference by settingsStore.themePreference.collectAsState()

    val presenceManager = remember {
        TvReceiverService.activePresenceManager
            ?: TvPresenceManager(context, pairedStore).apply { start() }
    }

    val activePeers by presenceManager.peers.collectAsState()
    val incomingPairRequests by TvPairingManager.activeIncomingRequests.collectAsState()
    val receivedFiles by historyStore.receivedFiles.collectAsState()
    val historyRecords by historyStore.history.collectAsState()

    val serviceProgress by TvReceiverService.activeReceiveProgress.collectAsState()
    val serviceAlert by TvReceiverService.latestAlertMessage.collectAsState()

    val scope = rememberCoroutineScope()
    val updateService = remember { TvUpdateService(context) }
    var availableUpdate by remember { mutableStateOf<TvUpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf<TvUpdateDownloadProgress?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var notificationMessage by remember { mutableStateOf<String?>(null) }

    val currentAppVersion = remember(context) {
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.45"
        } catch (_: Exception) {
            "1.0.45"
        }
    }

    val handleCheckForUpdates: () -> Unit = {
        if (!isCheckingUpdates && !isDownloadingUpdate) {
            isCheckingUpdates = true
            scope.launch {
                val info = updateService.checkGitHubReleases()
                isCheckingUpdates = false
                if (info.isUpdateAvailable && info.targetAsset != null) {
                    availableUpdate = info
                } else if (info.errorMessage != null) {
                    notificationMessage = "Update check: ${info.errorMessage}"
                } else {
                    notificationMessage = "SkyDrop is up to date (v$currentAppVersion)"
                }
            }
        }
    }

    // Check for updates on app launch only
    LaunchedEffect(Unit) {
        val info = updateService.checkUpdateOnLaunch()
        if (info != null && info.isUpdateAvailable && info.targetAsset != null) {
            availableUpdate = info
        }
    }

    LaunchedEffect(serviceAlert) {
        if (serviceAlert != null) {
            notificationMessage = serviceAlert
            delay(4000)
            notificationMessage = null
        }
    }

    LaunchedEffect(notificationMessage) {
        if (notificationMessage != null) {
            delay(4000)
            notificationMessage = null
        }
    }

    // Keep TvReceiverService running in the background as long as receiver is active
    LaunchedEffect(isReceiverActive) {
        if (isReceiverActive) {
            TvReceiverService.startService(context)
        } else {
            TvReceiverService.stopService(context)
        }
    }

    val deviceName = remember { TvIdentityManager.getDeviceName() }
    val localIp = remember { TvIdentityManager.getLocalIpAddress() }
    val wifiSsid = remember { TvIdentityManager.getWifiSsid(context) }
    var activePin by remember { mutableStateOf(TvPairingManager.getActivePin()) }

    var selectedItem by remember { mutableStateOf(TvNavItem.RECEIVED) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showManualConnectDialog by remember { mutableStateOf(false) }
    var manualConnectPeer by remember { mutableStateOf<PeerStatus?>(null) }
    var storageOverview by remember { mutableStateOf(TvStorageOverview(TvStorageSpace(0L, 0L))) }

    LaunchedEffect(selectedItem) {
        if (selectedItem == TvNavItem.SETTINGS || selectedItem == TvNavItem.DEVICES) {
            storageOverview = TvStorageStatsHelper.getStorageOverview(context)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(TvBgApp)) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Floating Remote-navigable Sidebar
            TvSidebar(
                selectedItem = selectedItem,
                onItemSelected = { selectedItem = it }
            )

            // Main Content Area with Status Header
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                TvHeader(
                    tvName = deviceName,
                    ipAddress = localIp,
                    wifiSsid = wifiSsid,
                    activePin = activePin,
                    onPairClick = {
                        showMyQrDialog = true
                    }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedItem) {
                        TvNavItem.RECEIVED -> TvReceivedMediaScreen(
                            files = receivedFiles,
                            isReceiverActive = isReceiverActive,
                            storagePath = saveLocation
                        )
                        TvNavItem.PAIR_DEVICE -> TvPairDeviceScreen(
                            pin = activePin,
                            tvName = deviceName,
                            ipAddress = localIp,
                            port = listeningPort
                        )
                        TvNavItem.DEVICES -> TvSettingsScreen(
                            peers = activePeers,
                            isReceiverActive = isReceiverActive,
                            onToggleReceiver = { settingsStore.setReceiverActive(it) },
                            onMyQrClick = { showMyQrDialog = true },
                            onManualIpClick = {
                                manualConnectPeer = null
                                showManualConnectDialog = true
                            },
                            onPairClick = { peer ->
                                manualConnectPeer = peer
                                showManualConnectDialog = true
                            },
                            onUnpairClick = { peerId ->
                                pairedStore.removeDevice(peerId)
                                presenceManager.unpairPeer(peerId)
                            },
                            onRetryClick = { peerId -> presenceManager.retryPeer(peerId) },
                            themePreference = themePreference,
                            onThemeChange = { settingsStore.setThemePreference(it) },
                            onOpenDownloadsClick = {
                                try {
                                    val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    notificationMessage = "Storage location: $saveLocation"
                                }
                            },
                            deviceName = deviceName,
                            saveLocation = saveLocation,
                            listeningPort = listeningPort,
                            storageOverview = storageOverview,
                            appVersion = currentAppVersion,
                            onCheckForUpdates = handleCheckForUpdates,
                            isCheckingUpdates = isCheckingUpdates
                        )
                        TvNavItem.HISTORY -> TvTransferHistoryScreen(
                            historyItems = historyRecords,
                            receivedFiles = receivedFiles
                        )
                        TvNavItem.SETTINGS -> TvSettingsScreen(
                            peers = activePeers,
                            isReceiverActive = isReceiverActive,
                            onToggleReceiver = { settingsStore.setReceiverActive(it) },
                            onMyQrClick = { showMyQrDialog = true },
                            onManualIpClick = {
                                manualConnectPeer = null
                                showManualConnectDialog = true
                            },
                            onPairClick = { peer ->
                                manualConnectPeer = peer
                                showManualConnectDialog = true
                            },
                            onUnpairClick = { peerId ->
                                pairedStore.removeDevice(peerId)
                                presenceManager.unpairPeer(peerId)
                            },
                            onRetryClick = { peerId -> presenceManager.retryPeer(peerId) },
                            themePreference = themePreference,
                            onThemeChange = { settingsStore.setThemePreference(it) },
                            onOpenDownloadsClick = {
                                try {
                                    val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    notificationMessage = "Storage location: $saveLocation"
                                }
                            },
                            deviceName = deviceName,
                            saveLocation = saveLocation,
                            listeningPort = listeningPort,
                            storageOverview = storageOverview,
                            appVersion = currentAppVersion,
                            onCheckForUpdates = handleCheckForUpdates,
                            isCheckingUpdates = isCheckingUpdates
                        )
                    }
                }
            }
        }

        // Unobtrusive Transfer Progress Window at Bottom Right
        TvTransferProgressOverlay(
            serviceProgress = serviceProgress,
            completionMessage = notificationMessage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 28.dp, end = 36.dp)
                .zIndex(10f)
        )
    }

    // Modal "My SkyDrop QR" Dialog
    if (showMyQrDialog) {
        TvQrDialog(
            onDismiss = { showMyQrDialog = false },
            pin = activePin,
            tvName = deviceName,
            ipAddress = localIp,
            port = listeningPort
        )
    }

    // Modal "Manual Connect" Dialog
    if (showManualConnectDialog) {
        TvManualConnectDialog(
            onDismiss = {
                showManualConnectDialog = false
                manualConnectPeer = null
            },
            onConnected = { peer ->
                activePin = TvPairingManager.getActivePin()
                presenceManager.onMdnsPeerDiscovered(peer)
            },
            initialHost = manualConnectPeer?.host ?: "",
            initialPort = manualConnectPeer?.port?.toString() ?: "8443",
            initialTls = manualConnectPeer?.isTls ?: (manualConnectPeer?.port == 8443)
        )
    }

    // Modal Inbound Pairing Request Dialog (displayed when Android phone or desktop asks to pair)
    incomingPairRequests.firstOrNull()?.let { req ->
        TvIncomingPairDialog(
            request = req,
            onAccept = {
                val pair = TvPairingManager.acceptIncomingRequest(req.requestId)
                if (pair != null) {
                    activePin = pair.second.secret
                    showMyQrDialog = true
                }
            },
            onDecline = {
                TvPairingManager.declineIncomingRequest(req.requestId)
            }
        )
    }

    // Modal GitHub Releases Update Prompt (D-pad navigable for TV remote)
    availableUpdate?.let { updateInfo ->
        TvUpdateDialog(
            updateInfo = updateInfo,
            downloadProgress = updateProgress,
            isDownloading = isDownloadingUpdate,
            onInstallClick = {
                val asset = updateInfo.targetAsset ?: return@TvUpdateDialog
                isDownloadingUpdate = true
                scope.launch {
                    val result = updateService.downloadApk(asset, updateInfo.checksumAsset) { prog ->
                        updateProgress = prog
                    }
                    isDownloadingUpdate = false
                    if (result.isSuccess) {
                        availableUpdate = null
                        updateService.promptInstallApk(result.getOrThrow())
                    } else {
                        notificationMessage = "Update download failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            },
            onDismiss = {
                availableUpdate = null
            }
        )
    }
}
