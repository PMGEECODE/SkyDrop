package com.famage.skydrop_tv_app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * TV Top-level navigation items.
 * Share and Clipboard are deferred as requested, keeping:
 * - Received Media: View received files, photos, videos.
 * - Paired Devices: Inspect connected and trusted peers.
 * - Pair New Device: Big screen TV QR code & pairing PIN display.
 * - Transfer History: Audit logs and completed transfers.
 * - Settings: Storage directories, port, and auto-receive options.
 */
enum class TvNavItem(
    val title: String,
    val icon: ImageVector,
    val subtitle: String
) {
    RECEIVED("Received Media", Icons.Default.Download, "Files & media stored on this TV"),
    PAIR_DEVICE("Pair Device", Icons.Default.QrCode, "Show QR code & PIN for mobile pairing"),
    DEVICES("Paired Devices", Icons.Default.Devices, "Manage connected phones & PCs"),
    HISTORY("Transfer History", Icons.Default.History, "Recent file transfers log"),
    SETTINGS("Settings", Icons.Default.Settings, "Daemon, storage & network options")
}
