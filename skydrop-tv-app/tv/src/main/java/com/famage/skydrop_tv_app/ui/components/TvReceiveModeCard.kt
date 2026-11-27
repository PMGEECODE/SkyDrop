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
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Receive Mode Card (Listening on local Wi-Fi) matching Android app teal card with port hidden.
 */
@Composable
fun TvReceiveModeCard(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val isDark = LocalTvColors.current.isDark
    val cardBg = if (isActive) {
        if (isDark) Color(0xFF0F3D39) else Color(0xFFCCFBF1)
    } else TvBgSurface
    val borderCol = if (isFocused) TvBorderFocused else if (isActive) TealAccent.copy(alpha = 0.4f) else TvBorder

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = borderCol,
                shape = RoundedCornerShape(16.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick(onClick = { onToggle(!isActive) })
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onToggle(!isActive)
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Receive Mode",
                color = TvTextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isActive) "Listening on local Wi-Fi • Ready to receive" else "Disabled • Toggle to receive files",
                color = if (isActive) TealAccentDark else TvTextSecondary,
                fontSize = 12.sp
            )
        }

        Switch(
            checked = isActive,
            onCheckedChange = onToggle
        )
    }
}

/**
 * Quick Action Button matching mobile SkyDrop quick connect buttons.
 */
@Composable
fun TvActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val isDark = LocalTvColors.current.isDark
    val defaultBg = if (isDark) Color(0xFF13343B) else Color(0xFFE0F2FE)

    Row(
        modifier = modifier
            .height(48.dp)
            .scale(if (isFocused) 1.03f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TvBgElevated else defaultBg)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TealAccent.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
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
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TealAccentDark,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = TvTextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
