package com.famage.skydrop_tv_app.ui.screens

import android.view.SoundEffectConstants
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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvPairedPeer
import com.famage.skydrop_tv_app.ui.components.tvFocusSound
import com.famage.skydrop_tv_app.ui.theme.*

@Composable
fun TvPairedDevicesScreen(
    peers: List<TvPairedPeer> = emptyList(),
    onPairNewClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TRUSTED & PAIRED PEERS (${peers.size})",
                color = TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            TvPairNewButton(onClick = onPairNewClick)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (peers.isEmpty()) {
            // Clean empty state ready for real peer authorization
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(TvBgSurface)
                    .border(1.dp, TvBorder, RoundedCornerShape(18.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(TvBgElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "No Paired Devices",
                        color = TvTextMain,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Pair your phone or computer using the 6-digit code or QR scanner to establish mutual TLS trust.",
                        color = TvTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(peers.size) { index -> val peer = peers[index]
                    TvPeerCard(peer = peer)
                }
            }
        }
    }
}

@Composable
private fun TvPairNewButton(onClick: () -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) SkyBlueDark else TvBgSurface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TvBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .tvFocusSound(interactionSource)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.QrCode,
            contentDescription = null,
            tint = if (isFocused) SkyBlue else TealAccent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Pair New Device",
            color = TvTextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TvPeerCard(peer: TvPairedPeer) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) TvBgElevated else TvBgSurface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TvBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .tvFocusSound(interactionSource)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                }
            )
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SkyBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = SkyBlue,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Text(
                    text = peer.name,
                    color = TvTextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${peer.host}:${peer.port}",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (peer.isOnline) SuccessGreen else TvTextMuted, CircleShape)
            )
            Text(
                text = if (peer.isOnline) "ONLINE" else "OFFLINE",
                color = if (peer.isOnline) SuccessGreen else TvTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
