package com.famage.skydrop_tv_app.ui.components

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvStorageOverview
import com.famage.skydrop_tv_app.model.TvStorageSpace
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Device & Storage Info Card displaying device metadata, total/used/remaining storage,
 * and dynamically showing any plugged-in removable media (USB/SD).
 */
@Composable
fun TvDeviceStorageInfoCard(
    deviceName: String,
    saveLocation: String,
    storageOverview: TvStorageOverview,
    onOpenDownloadsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val openInteractionSource = remember { MutableInteractionSource() }
    val isOpenFocused by openInteractionSource.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TvBgSurface)
            .border(1.dp, TvBorder, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Device & Storage Info",
            color = TvTextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )

        // Item 1: Device Name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Smartphone,
                contentDescription = null,
                tint = SkyBlue,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Device Name",
                    color = TvTextMuted,
                    fontSize = 11.sp
                )
                Text(
                    text = deviceName,
                    color = TvTextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TvBorder)
        )

        // Item 2: Internal Storage (Total, Used, Remaining + Progress Meter)
        StorageUsageRow(
            title = "Internal Storage",
            subtitle = if (storageOverview.internalStorage.totalBytes > 0L) {
                "${storageOverview.internalStorage.usedFormatted} used of ${storageOverview.internalStorage.totalFormatted} • ${storageOverview.internalStorage.freeFormatted} remaining"
            } else {
                "Storage status unavailable"
            },
            icon = Icons.Default.Storage,
            space = storageOverview.internalStorage
        )

        // Item 3: Removable Media (USB / SD cards) - ONLY DISPLAYED IF MEDIA IS PLUGGED IN
        if (storageOverview.removableMedia.isNotEmpty()) {
            for (media in storageOverview.removableMedia) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(TvBorder)
                )

                StorageUsageRow(
                    title = "External Drive: ${media.name}",
                    subtitle = if (media.space.totalBytes > 0L) {
                        "${media.space.usedFormatted} used of ${media.space.totalFormatted} • ${media.space.freeFormatted} remaining"
                    } else {
                        "Mounted Removable Storage"
                    },
                    icon = Icons.Default.Usb,
                    space = media.space
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TvBorder)
        )

        // Item 4: Save Location
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = SkyBlue,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Save Location",
                        color = TvTextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = saveLocation,
                        color = SkyBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (onOpenDownloadsClick != null) {
                Box(
                    modifier = Modifier
                        .scale(if (isOpenFocused) 1.08f else 1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isOpenFocused) TvBgElevated else TvBgSubtle)
                        .border(
                            width = if (isOpenFocused) 1.5.dp else 1.dp,
                            color = if (isOpenFocused) TvBorderFocused else TvBorder,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .tvFocusSound(openInteractionSource)
                        .tvRemoteClick(onClick = onOpenDownloadsClick)
                        .clickable(
                            interactionSource = openInteractionSource,
                            indication = null,
                            onClick = {
                                view.playSoundEffect(SoundEffectConstants.CLICK)
                                onOpenDownloadsClick()
                            }
                        )
                        .focusable(interactionSource = openInteractionSource)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Open ↗",
                        color = SkyBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Reusable row displaying storage title, icon, formatted metrics and a clean progress bar.
 */
@Composable
private fun StorageUsageRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    space: TvStorageSpace
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TealAccentDark,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        color = TvTextMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        color = TvTextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            if (space.totalBytes > 0L) {
                Text(
                    text = "${space.usedPercent}% used",
                    color = if (space.usedPercent > 90) TvTextMuted else TealAccentDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (space.totalBytes > 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(TvBgSubtle)
            ) {
                val fraction = (space.usedPercent / 100f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (space.usedPercent > 90) Color(0xFFEF4444) else TealAccentDark
                        )
                )
            }
        }
    }
}
