package com.famage.skydrop_tv_app.ui.components

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.model.PresenceState
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Individual device card on TV:
 * - Device icon
 * - Status dot + Device Name + Status Badge
 * - Host IP subtitle (port hidden)
 * - Action buttons: [Pair], [Retry], [Unpair]
 */
@Composable
fun TvDeviceCard(
    peer: PeerStatus,
    onPairClick: () -> Unit = {},
    onUnpairClick: () -> Unit = {},
    onRetryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deviceIcon = when {
        peer.name.lowercase().contains("tv") -> Icons.Default.Tv
        peer.name.lowercase().contains("laptop") || peer.name.lowercase().contains("mac") -> Icons.Default.Laptop
        peer.name.lowercase().contains("pc") || peer.name.lowercase().contains("desktop") || peer.name.lowercase().contains("cdncode") -> Icons.Default.Computer
        else -> Icons.Default.Smartphone
    }

    val dotColor = when {
        !peer.isPaired -> SkyBlue
        peer.presenceState == PresenceState.ONLINE -> SuccessGreen
        peer.presenceState == PresenceState.OFFLINE -> TvTextMuted
        peer.presenceState == PresenceState.ERROR -> DangerRed
        else -> TvTextSecondary
    }

    val (badgeText, badgeBg, badgeTextColor) = when {
        !peer.isPaired -> Triple("Ready to Pair", TvBgSubtle, TvTextSecondary)
        peer.presenceState == PresenceState.ONLINE -> Triple(
            if (peer.latencyMs > 0) "ONLINE • ${peer.latencyMs}ms" else "ONLINE",
            SkyBlue.copy(alpha = 0.2f),
            SkyBlue
        )
        peer.presenceState == PresenceState.OFFLINE -> Triple("OFFLINE", TvBgSubtle, TvTextMuted)
        peer.presenceState == PresenceState.ERROR -> Triple("TOKEN REVOKED", DangerRed.copy(alpha = 0.2f), DangerRed)
        else -> Triple("CHECKING", TvBgSubtle, TvTextSecondary)
    }

    val subtitle = when {
        peer.canSend -> "${peer.host} • Ready"
        !peer.isPaired -> "${peer.host} • Ready to pair"
        peer.presenceState == PresenceState.OFFLINE -> "${peer.host} • Failed to connect"
        peer.presenceState == PresenceState.ERROR -> "${peer.host} • Pairing token expired"
        else -> "${peer.host} • Offline"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TvBgSurface)
            .border(
                width = 1.dp,
                color = TvBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Device Info
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SkyBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = deviceIcon,
                    contentDescription = null,
                    tint = SkyBlue,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(dotColor, CircleShape)
                    )
                    Text(
                        text = peer.name,
                        color = TvTextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeTextColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = subtitle,
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Action Buttons Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (peer.isPaired) {
                if (!peer.canSend) {
                    TvRetryButton(onClick = onRetryClick)
                }
                TvUnpairButton(onClick = onUnpairClick)
            } else {
                TvPairButton(onClick = onPairClick)
            }
        }
    }
}

@Composable
private fun TvRetryButton(onClick: () -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) TvBgElevated else Color.Transparent)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TvBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick(onClick = onClick)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            tint = if (isFocused) TvTextMain else TvTextSecondary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = "Retry",
            color = if (isFocused) TvTextMain else TvTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TvUnpairButton(onClick: () -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) DangerRed.copy(alpha = 0.2f) else Color.Transparent)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) DangerRed else DangerRed.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick(onClick = onClick)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Unpair",
            color = DangerRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TvPairButton(onClick: () -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) SkyBlueDark else SkyBlue)
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) TvBorderFocused else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick(onClick = onClick)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Pair",
            color = TvTextMain,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
