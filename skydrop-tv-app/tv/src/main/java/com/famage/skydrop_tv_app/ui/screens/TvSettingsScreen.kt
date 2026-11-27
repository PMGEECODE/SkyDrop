package com.famage.skydrop_tv_app.ui.screens

import android.view.SoundEffectConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.R
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.TvStorageOverview
import com.famage.skydrop_tv_app.model.TvStorageSpace
import com.famage.skydrop_tv_app.ui.components.*
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * TV Settings & Devices screen matching SkyDrop TV receiving architecture:
 * 1. Active Paired Device Status Banner (no send/drop button needed on TV)
 * 2. Receive Mode (Listening on local Wi-Fi switch)
 * 3. Quick Action Row: My QR & Manual Connect
 * 4. Nearby & Paired Devices List with Unpair & Retry (ports hidden)
 * 5. Appearance (System / Light / Dark Theme)
 * 6. Device & Storage Info (TCL/Samsung model, Downloads/SkyDrop, Open ↗)
 * 7. SkyDrop text logo and Version footer
 */
@Composable
fun TvSettingsScreen(
    peers: List<PeerStatus> = emptyList(),
    isReceiverActive: Boolean = true,
    onToggleReceiver: (Boolean) -> Unit = {},
    onMyQrClick: () -> Unit = {},
    onManualIpClick: () -> Unit = {},
    onPairClick: (PeerStatus) -> Unit = {},
    onUnpairClick: (String) -> Unit = {},
    onRetryClick: (String) -> Unit = {},
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    onThemeChange: (ThemePreference) -> Unit = {},
    storageOverview: TvStorageOverview = TvStorageOverview(TvStorageSpace(0L, 0L)),
    onOpenDownloadsClick: (() -> Unit)? = null,
    deviceName: String = "Smart_TV",
    saveLocation: String = "Downloads/SkyDrop",
    listeningPort: Int = 8444,
    appVersion: String = "",
    onCheckForUpdates: (() -> Unit)? = null,
    isCheckingUpdates: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentAppVersion = remember(appVersion, context) {
        if (appVersion.isNotBlank() && appVersion != "1.0.0") {
            appVersion
        } else {
            try {
                val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
    }

    val activeConnected = remember(peers) { peers.firstOrNull { it.canSend } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 2. Receive Mode Card (Listening on local Wi-Fi, port hidden)
        item(key = "receive_mode_card") {
            TvReceiveModeCard(
                isActive = isReceiverActive,
                onToggle = onToggleReceiver
            )
        }

        // 2b. Display Over Other Apps Permission Card
        item(key = "overlay_permission_card") {
            TvOverlayPermissionCard()
        }

        // 3. Quick Action Buttons Row (My QR & Manual Connect - camera scan removed for TV)
        item(key = "quick_actions_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvActionButton(
                    icon = Icons.Default.QrCode,
                    label = "My QR",
                    onClick = onMyQrClick,
                    modifier = Modifier.weight(1f)
                )

                TvActionButton(
                    icon = Icons.Default.Language,
                    label = "Manual Connect",
                    onClick = onManualIpClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. Section Header: Nearby & Paired Devices
        item(key = "nearby_devices_header") {
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby & Paired Devices",
                    color = TvTextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${peers.size} found",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        // 4b. Devices List or Empty State
        if (peers.isEmpty()) {
            item(key = "empty_peers_box") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(TvBgSurface)
                        .border(1.dp, TvBorder, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Searching for devices on local Wi-Fi...",
                            color = TvTextSecondary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Open SkyDrop on your phone or desktop to connect",
                            color = TvTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        } else {
            items(peers, key = { "device_${it.id}_${it.host}_${it.port}" }) { peer ->
                TvDeviceCard(
                    peer = peer,
                    onPairClick = { onPairClick(peer) },
                    onUnpairClick = { onUnpairClick(peer.id) },
                    onRetryClick = { onRetryClick(peer.id) }
                )
            }
        }

        // 5. Appearance Card (System / Light / Dark Theme)
        item(key = "appearance_card") {
            TvAppearanceCard(
                currentPreference = themePreference,
                onPreferenceChange = onThemeChange
            )
        }

        // 6. Device & Storage Info Card
        item(key = "storage_info_card") {
            TvDeviceStorageInfoCard(
                deviceName = deviceName,
                saveLocation = saveLocation,
                storageOverview = storageOverview,
                onOpenDownloadsClick = onOpenDownloadsClick
            )
        }

        // 7. Official SkyDrop Logo & Version Footer with Check for Updates action
        item(key = "footer_version_box") {
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(if (isFocused) 1.02f else 1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isFocused) TvBorder.copy(alpha = 0.3f) else TvBgSurface)
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = if (isFocused) SkyBlue else TvBorder,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .focusable(interactionSource = interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = !isCheckingUpdates
                    ) {
                        onCheckForUpdates?.invoke()
                    }
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.skydrop_text),
                        contentDescription = "SkyDrop Logo",
                        modifier = Modifier
                            .height(26.dp)
                            .wrapContentWidth()
                    )
                    Text(
                        text = if (isCheckingUpdates) {
                            "Checking for updates..."
                        } else {
                            "Version $currentAppVersion • Click to check for updates ↗"
                        },
                        color = if (isFocused) SkyBlue else TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * Blue Active Connection Banner without drop files button, showing only peer IP.
 */
@Composable
private fun TvActivePairedBanner(
    peer: PeerStatus
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SkyBlueDark)
            .border(
                width = 1.dp,
                color = SkyBlue.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SkyBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = TvTextMain,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(SuccessGreen, CircleShape)
                    )
                    Text(
                        text = "Paired with ${peer.name}",
                        color = TvTextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${peer.host} • Ready for instant drops",
                    color = TvTextMain.copy(alpha = 0.8f),
                    fontSize = 12.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SuccessGreen.copy(alpha = 0.15f))
                .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "CONNECTED",
                color = SuccessGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

