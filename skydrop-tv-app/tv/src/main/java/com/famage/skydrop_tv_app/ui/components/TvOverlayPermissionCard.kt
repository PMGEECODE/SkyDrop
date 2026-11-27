package com.famage.skydrop_tv_app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.receiver.TvSystemOverlayManager
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Settings Card to view status of and grant "Display Over Other Apps" permission.
 */
@Composable
fun TvOverlayPermissionCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    var hasPermission by remember {
        mutableStateOf(TvSystemOverlayManager.canDrawOverlays(context))
    }

    fun openSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (hasPermission) TvBgSurface else SkyBlue.copy(alpha = 0.1f))
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else if (hasPermission) TvBorder else SkyBlue.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick { openSettings() }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    openSettings()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.PictureInPicture,
                contentDescription = null,
                tint = if (hasPermission) SuccessGreen else SkyBlue,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Display Over Other Apps",
                    color = TvTextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (hasPermission) {
                        "Enabled • Incoming file progress shows over YouTube and other apps"
                    } else {
                        "Disabled • Click to allow floating progress over YouTube & other apps"
                    },
                    color = if (hasPermission) SuccessGreen else SkyBlue,
                    fontSize = 12.sp
                )
            }
        }

        Switch(
            checked = hasPermission,
            onCheckedChange = { openSettings() }
        )
    }
}
