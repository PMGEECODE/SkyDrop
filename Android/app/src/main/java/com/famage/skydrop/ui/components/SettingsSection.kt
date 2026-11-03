package com.famage.skydrop.ui.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famage.skydrop.R
import com.famage.skydrop.model.Device
import com.famage.skydrop.model.PeerStatus
import com.famage.skydrop.model.TransferState

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsSection(
    peers: List<PeerStatus>,
    queuedCount: Int = 0,
    isReceiverActive: Boolean,
    transferState: TransferState,
    onToggleReceiver: (Boolean) -> Unit,
    onScanQrClick: () -> Unit,
    onManualIpClick: () -> Unit,
    onPairClick: (Device) -> Unit,
    onUnpairClick: (String) -> Unit,
    onSendClick: (Device) -> Unit,
    onRetryPeer: ((String) -> Unit)? = null,
    onExportDiagnostics: (() -> Unit)? = null,
    onOpenDownloadsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    } else {
        true
    }

    val activeConnected = peers.firstOrNull { it.isPaired && it.canSend }
    val offlinePaired = peers.firstOrNull { it.isPaired && !it.canSend }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 840.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Section: Live Connectivity
        item {
            Text(
                text = "Connection & Pairing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (activeConnected != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSendClick(activeConnected.toDevice()) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🟢", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Paired with ${activeConnected.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                            Text(
                                text = "${activeConnected.host}:${activeConnected.port} • Ready for instant drops",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32)
                            )
                        }
                        Text(
                            text = if (queuedCount > 0) "Send ($queuedCount) →" else "Drop Files →",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
            }
        } else if (offlinePaired != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔴", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${offlinePaired.name} is offline",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB71C1C)
                            )
                            Text(
                                text = "Will reconnect automatically when device joins Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC62828)
                            )
                        }
                    }
                }
            }
        }

        // Section: Receive Mode
        item {
            ReceiverModeCard(
                isActive = isReceiverActive,
                onToggle = onToggleReceiver
            )
        }

        // Section: Pair Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onScanQrClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("📷 Scan QR")
                }
                OutlinedButton(
                    onClick = onManualIpClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🌐 Manual IP")
                }
            }
        }

        // Section: Discovered & Paired Peers Header
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby & Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${peers.size} found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (peers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Discovering devices on local Wi-Fi...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ensure both devices are on the same network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(peers.distinctBy { it.id }, key = { it.id }) { peer ->
                DeviceCard(
                    peer = peer,
                    enabled = (transferState is TransferState.Idle || transferState is TransferState.Success || transferState is TransferState.Error),
                    queuedCount = queuedCount,
                    onSendClick = { onSendClick(peer.toDevice()) },
                    onPairClick = { onPairClick(peer.toDevice()) },
                    onUnpairClick = { onUnpairClick(peer.id) },
                    onRetryClick = { onRetryPeer?.invoke(peer.id) }
                )
            }
        }

        // Section: Battery Optimization Prompt (if restricted)
        if (!isIgnoringBattery) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚡", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Battery Optimization Active",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Android may sleep or delay incoming drops when screen is off. Request an exemption to allow reliable background transfers in Doze mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                } catch (_: Exception) {
                                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00))
                        ) {
                            Text("Allow Background Transfers")
                        }
                    }
                }
            }
        }

        // Section: Diagnostics Report
        if (onExportDiagnostics != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Diagnostics & Presence Report",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Export system telemetry, connection logs, and peer states",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onExportDiagnostics) {
                            Text("Export JSON")
                        }
                    }
                }
            }
        }

        // Section: Device Info & Storage Location
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpenDownloadsClick != null) Modifier.clickable { onOpenDownloadsClick() }
                        else Modifier
                    ),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Device & Storage Info",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Device Name: ${Build.MANUFACTURER} ${Build.MODEL}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Receiver Port: 8444 (HTTP)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "• Save Location: Downloads/SkyDrop",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (onOpenDownloadsClick != null) {
                            Text(
                                text = "Open ↗",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.skydrop_text),
                        contentDescription = "SkyDrop Logo",
                        modifier = Modifier
                            .height(30.dp)
                            .wrapContentWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Version 1.0.0 • Local Peer-to-Peer Drop",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
}

