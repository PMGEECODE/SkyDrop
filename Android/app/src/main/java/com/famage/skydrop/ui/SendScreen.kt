package com.famage.skydrop.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famage.skydrop.R
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.TransferState
import com.famage.skydrop.ui.components.*
import kotlinx.coroutines.launch

enum class MainNavigationTab {
    MEDIA,
    SETTINGS
}

enum class MediaSubTab {
    RECEIVED,
    SEND
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: SendViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val peers by viewModel.peerStatuses.collectAsState()
    val queuedItems by viewModel.queuedItems.collectAsState()
    val sharedText by viewModel.sharedText.collectAsState()
    val transferState by viewModel.transferState.collectAsState()
    val isReceiverActive by viewModel.isReceiverActive.collectAsState()
    val transferHistory by viewModel.transferHistory.collectAsState()
    val receiveProgress by viewModel.receiveProgress.collectAsState()

    var activeMainTab by remember { mutableStateOf(MainNavigationTab.MEDIA) }
    var activeMediaSubTab by remember { mutableStateOf(MediaSubTab.RECEIVED) }

    var showManualDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var pairingDevice by remember { mutableStateOf<Device?>(null) }
    var isPairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        viewModel.syncExistingDownloads()
    }

    // Auto-navigate to Received Media when an inbound transfer begins
    LaunchedEffect(receiveProgress != null) {
        if (receiveProgress != null) {
            if (activeMainTab != MainNavigationTab.MEDIA) {
                activeMainTab = MainNavigationTab.MEDIA
            }
            if (activeMediaSubTab != MediaSubTab.RECEIVED) {
                activeMediaSubTab = MediaSubTab.RECEIVED
            }
        }
    }

    // Auto-navigate to Received Media when a new inbound file is completed
    val latestInboundId = remember(transferHistory) {
        transferHistory.firstOrNull { it.direction == "inbound" }?.id
    }
    var previousInboundId by remember { mutableStateOf(latestInboundId) }
    LaunchedEffect(latestInboundId) {
        if (previousInboundId != null && latestInboundId != null && latestInboundId != previousInboundId) {
            activeMainTab = MainNavigationTab.MEDIA
            activeMediaSubTab = MediaSubTab.RECEIVED
        }
        previousInboundId = latestInboundId
    }

    val activeConnected = peers.firstOrNull { it.isPaired && it.isConnected }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val isWideScreen = screenWidth >= 600.dp
        val isDualPane = screenWidth >= 840.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.skydrop_logo),
                                contentDescription = "SkyDrop Logo",
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SkyDrop",
                                fontWeight = FontWeight.Bold,
                                style = if (screenWidth < 360.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge
                            )
                            if (activeConnected != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Color(0xFFE8F5E9),
                                    modifier = Modifier.clickable { activeMainTab = MainNavigationTab.SETTINGS }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🟢", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = activeConnected.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1B5E20),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = { showQrScanner = true },
                            contentPadding = PaddingValues(
                                horizontal = if (screenWidth < 360.dp) 8.dp else 12.dp,
                                vertical = 6.dp
                            ),
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                .padding(end = 8.dp)
                        ) {
                            if (screenWidth < 360.dp) {
                                Text("📷", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text("📷 Scan QR", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = activeMainTab == MainNavigationTab.MEDIA,
                            onClick = { activeMainTab = MainNavigationTab.MEDIA },
                            label = { Text("Media Hub") },
                            icon = {
                                val count = if (receiveProgress != null) "⚡" else "${transferHistory.size}"
                                BadgedBox(
                                    badge = {
                                        if (transferHistory.isNotEmpty() || receiveProgress != null) {
                                            Badge { Text(count) }
                                        }
                                    }
                                ) {
                                    Text("📁", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        )
                        NavigationBarItem(
                            selected = activeMainTab == MainNavigationTab.SETTINGS,
                            onClick = { activeMainTab = MainNavigationTab.SETTINGS },
                            label = { Text("Settings & Devices") },
                            icon = {
                                Text("⚙️", style = MaterialTheme.typography.titleMedium)
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                // Navigation Rail for Wide Screens (Tablets, Foldables, Desktop)
                if (isWideScreen) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = activeMainTab == MainNavigationTab.MEDIA || isDualPane,
                            onClick = { activeMainTab = MainNavigationTab.MEDIA },
                            label = { Text("Media Hub") },
                            icon = {
                                val count = if (receiveProgress != null) "⚡" else "${transferHistory.size}"
                                BadgedBox(
                                    badge = {
                                        if (transferHistory.isNotEmpty() || receiveProgress != null) {
                                            Badge { Text(count) }
                                        }
                                    }
                                ) {
                                    Text("📁", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        )
                        if (!isDualPane) {
                            NavigationRailItem(
                                selected = activeMainTab == MainNavigationTab.SETTINGS,
                                onClick = { activeMainTab = MainNavigationTab.SETTINGS },
                                label = { Text("Settings") },
                                icon = {
                                    Text("⚙️", style = MaterialTheme.typography.titleMedium)
                                }
                            )
                        }
                    }
                }

                // Main Content Column / Dual Pane Split View
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Global Transfer Status banner
                    if (transferState !is TransferState.Idle) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            TransferStatusCard(
                                state = transferState,
                                onDismiss = { viewModel.resetTransferState() }
                            )
                        }
                    }

                    if (isDualPane) {
                        // Dual Pane Layout (Tablets / Foldables / Unfolded Duo / Desktops)
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Left Pane: Media Hub
                            Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                                PrimaryTabRow(
                                    selectedTabIndex = activeMediaSubTab.ordinal
                                ) {
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.RECEIVED,
                                        onClick = { activeMediaSubTab = MediaSubTab.RECEIVED },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Received Media")
                                                if (receiveProgress != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("Live") }
                                                } else if (transferHistory.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("${transferHistory.size}") }
                                                }
                                            }
                                        }
                                    )
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.SEND,
                                        onClick = { activeMediaSubTab = MediaSubTab.SEND },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Send Files")
                                                if (queuedItems.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("${queuedItems.size}") }
                                                }
                                            }
                                        }
                                    )
                                }

                                when (activeMediaSubTab) {
                                    MediaSubTab.RECEIVED -> {
                                        ReceivedMediaSection(
                                            items = transferHistory,
                                            receiveProgress = receiveProgress,
                                            onRefresh = { viewModel.syncExistingDownloads() },
                                            onDelete = { item -> viewModel.deleteHistoryItem(item.id) },
                                            onDeleteMultiple = { ids -> viewModel.deleteHistoryItems(ids) },
                                            onSendAgain = { item ->
                                                viewModel.queueHistoryItemForSend(item)
                                                activeMediaSubTab = MediaSubTab.SEND
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    MediaSubTab.SEND -> {
                                        SendMediaSection(
                                            peers = peers,
                                            queuedItems = queuedItems,
                                            sharedText = sharedText,
                                            transferState = transferState,
                                            onAddUris = { uris -> viewModel.addSharedUris(uris) },
                                            onRemoveItem = { item -> viewModel.removeQueuedItem(item) },
                                            onClearAll = { viewModel.clearQueuedItems() },
                                            onSetSharedText = { text -> viewModel.setSharedText(text) },
                                            onSendPayload = { peer -> viewModel.sendToDevice(peer.toDevice()) },
                                            onResetTransferState = { viewModel.resetTransferState() },
                                            onNavigateToSettings = { activeMainTab = MainNavigationTab.SETTINGS },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            VerticalDivider()

                            // Right Pane: Settings & Devices
                            SettingsSection(
                                peers = peers,
                                queuedCount = queuedItems.size + (if (sharedText.isNullOrBlank()) 0 else 1),
                                isReceiverActive = isReceiverActive,
                                transferState = transferState,
                                onToggleReceiver = { viewModel.toggleReceiver(it) },
                                onScanQrClick = { showQrScanner = true },
                                onManualIpClick = { showManualDialog = true },
                                onPairClick = { device ->
                                    pairingDevice = device
                                    pairError = null
                                },
                                onUnpairClick = { id -> viewModel.unpairDevice(id) },
                                onSendClick = { device ->
                                    if (queuedItems.isNotEmpty() || !sharedText.isNullOrBlank()) {
                                        viewModel.sendToDevice(device)
                                    }
                                    activeMainTab = MainNavigationTab.MEDIA
                                    activeMediaSubTab = MediaSubTab.SEND
                                },
                                onRetryPeer = { id -> viewModel.retryPeer(id) },
                                onExportDiagnostics = {
                                    val report = viewModel.getDiagnosticsReport()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("SkyDrop Diagnostics", report))
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("📋 Diagnostics report copied to clipboard")
                                    }
                                    try {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_TEXT, report)
                                            putExtra(Intent.EXTRA_SUBJECT, "SkyDrop Diagnostics Report")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Export Diagnostics"))
                                    } catch (_: Exception) {}
                                },
                                onOpenDownloadsClick = {
                                    try {
                                        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.weight(0.9f)
                            )
                        }
                    } else {
                        // Single Pane Layout (Phones / Narrow screens)
                        when (activeMainTab) {
                            MainNavigationTab.MEDIA -> {
                                PrimaryTabRow(
                                    selectedTabIndex = activeMediaSubTab.ordinal
                                ) {
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.RECEIVED,
                                        onClick = { activeMediaSubTab = MediaSubTab.RECEIVED },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Received Media")
                                                if (receiveProgress != null) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("Live") }
                                                } else if (transferHistory.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("${transferHistory.size}") }
                                                }
                                            }
                                        }
                                    )
                                    Tab(
                                        selected = activeMediaSubTab == MediaSubTab.SEND,
                                        onClick = { activeMediaSubTab = MediaSubTab.SEND },
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Send Files")
                                                if (queuedItems.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text("${queuedItems.size}") }
                                                }
                                            }
                                        }
                                    )
                                }

                                when (activeMediaSubTab) {
                                    MediaSubTab.RECEIVED -> {
                                        ReceivedMediaSection(
                                            items = transferHistory,
                                            receiveProgress = receiveProgress,
                                            onRefresh = { viewModel.syncExistingDownloads() },
                                            onDelete = { item -> viewModel.deleteHistoryItem(item.id) },
                                            onDeleteMultiple = { ids -> viewModel.deleteHistoryItems(ids) },
                                            onSendAgain = { item ->
                                                viewModel.queueHistoryItemForSend(item)
                                                activeMediaSubTab = MediaSubTab.SEND
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    MediaSubTab.SEND -> {
                                        SendMediaSection(
                                            peers = peers,
                                            queuedItems = queuedItems,
                                            sharedText = sharedText,
                                            transferState = transferState,
                                            onAddUris = { uris -> viewModel.addSharedUris(uris) },
                                            onRemoveItem = { item -> viewModel.removeQueuedItem(item) },
                                            onClearAll = { viewModel.clearQueuedItems() },
                                            onSetSharedText = { text -> viewModel.setSharedText(text) },
                                            onSendPayload = { peer -> viewModel.sendToDevice(peer.toDevice()) },
                                            onResetTransferState = { viewModel.resetTransferState() },
                                            onNavigateToSettings = { activeMainTab = MainNavigationTab.SETTINGS },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            MainNavigationTab.SETTINGS -> {
                                SettingsSection(
                                    peers = peers,
                                    queuedCount = queuedItems.size + (if (sharedText.isNullOrBlank()) 0 else 1),
                                    isReceiverActive = isReceiverActive,
                                    transferState = transferState,
                                    onToggleReceiver = { viewModel.toggleReceiver(it) },
                                    onScanQrClick = { showQrScanner = true },
                                    onManualIpClick = { showManualDialog = true },
                                    onPairClick = { device ->
                                        pairingDevice = device
                                        pairError = null
                                    },
                                    onUnpairClick = { id -> viewModel.unpairDevice(id) },
                                    onSendClick = { device ->
                                        if (queuedItems.isNotEmpty() || !sharedText.isNullOrBlank()) {
                                            viewModel.sendToDevice(device)
                                        }
                                        activeMainTab = MainNavigationTab.MEDIA
                                        activeMediaSubTab = MediaSubTab.SEND
                                    },
                                    onRetryPeer = { id -> viewModel.retryPeer(id) },
                                    onExportDiagnostics = {
                                        val report = viewModel.getDiagnosticsReport()
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("SkyDrop Diagnostics", report))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("📋 Diagnostics report copied to clipboard")
                                        }
                                        try {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_TEXT, report)
                                                putExtra(Intent.EXTRA_SUBJECT, "SkyDrop Diagnostics Report")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Export Diagnostics"))
                                        } catch (_: Exception) {}
                                    },
                                    onOpenDownloadsClick = {
                                        try {
                                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualDeviceDialog(
            onDismiss = { showManualDialog = false },
            onAdd = { host, port, isTls ->
                viewModel.addManualDevice(host, port, isTls)
                showManualDialog = false
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onQrScanned = { rawUri ->
                showQrScanner = false
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Pairing with scanned QR code...")
                }
                viewModel.pairWithUri(rawUri) { success, err ->
                    coroutineScope.launch {
                        if (success) {
                            snackbarHostState.showSnackbar("✅ Device paired successfully!")
                        } else {
                            snackbarHostState.showSnackbar("❌ Pairing failed: ${err ?: "Connection error"}")
                        }
                    }
                }
            }
        )
    }

    pairingDevice?.let { targetDevice ->
        PairDeviceDialog(
            device = targetDevice,
            isPairing = isPairing,
            errorMessage = pairError,
            onDismiss = {
                pairingDevice = null
                pairError = null
                isPairing = false
            },
            onScanQrClick = {
                pairingDevice = null
                pairError = null
                isPairing = false
                showQrScanner = true
            },
            onConfirmPair = { secret ->
                isPairing = true
                pairError = null
                viewModel.pairWithSecret(targetDevice, secret) { success, err ->
                    isPairing = false
                    if (success) {
                        pairingDevice = null
                        pairError = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("✅ Successfully paired with ${targetDevice.name}")
                        }
                    } else {
                        pairError = err ?: "Pairing failed"
                    }
                }
            }
        )
    }
}
