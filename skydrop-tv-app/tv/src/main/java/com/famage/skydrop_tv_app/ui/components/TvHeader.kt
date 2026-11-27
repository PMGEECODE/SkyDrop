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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.*
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
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Top Status Header for TV experience with remote sound feedback.
 */
@Composable
fun TvHeader(
    tvName: String = "Android TV",
    ipAddress: String = "8080",
    wifiSsid: String = "Local Wi-Fi",
    activePin: String = "",
    onPairClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Device & Network summary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TvBgSurface)
                    .border(1.dp, TvBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = SkyBlue,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = tvName,
                        color = TvTextMain,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(SuccessGreen, CircleShape)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = TvTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = wifiSsid,
                            color = TvTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Text(
                        text = "•",
                        color = TvTextMuted,
                        fontSize = 12.sp
                    )

                    Text(
                        text = ipAddress,
                        color = TealAccentDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Right: TV Quick Pair Code Pill (Remote Focusable with sound)
        TvPairingPill(
            pin = activePin,
            onClick = onPairClick
        )
    }
}

@Composable
private fun TvPairingPill(
    pin: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) SkyBlueDark else TvBgSurface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TvBorder,
                shape = RoundedCornerShape(16.dp)
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.QrCode,
            contentDescription = "Pair Code",
            tint = if (isFocused) SkyBlue else TealAccent,
            modifier = Modifier.size(20.dp)
        )

        Column {
            Text(
                text = "PAIR CODE",
                color = TvTextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = if (pin.isNotBlank()) pin else "PAIR",
                color = TvTextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
        }
    }
}
