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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Onboarding / First-Launch Permission Prompt for "Display Over Other Apps".
 * Allows users to enable floating progress overlay with a single click.
 */
@Composable
fun TvOverlayPermissionDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val enableFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        try {
            enableFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    fun openOverlaySettings() {
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
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {}
            }
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvBgElevated)
                    .border(1.5.dp, TvBorder, RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(SkyBlue.copy(alpha = 0.15f))
                            .border(1.5.dp, SkyBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPicture,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Display Over Other Apps",
                            color = TvTextMain,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Allow SkyDrop to show incoming file progress even while you are using YouTube, Netflix, or the TV Home Screen.",
                            color = TvTextMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )

                        Text(
                            text = "Click 'Enable Permission' to toggle the switch in TV settings. It will not interrupt TV remote controls or playback.",
                            color = TvTextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val enableInteraction = remember { MutableInteractionSource() }
                        val enableFocused by enableInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1.4f)
                                .height(48.dp)
                                .scale(if (enableFocused) 1.03f else 1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (enableFocused) SkyBlueDark else SkyBlue)
                                .border(
                                    1.5.dp,
                                    if (enableFocused) Color.White else Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                                .tvFocusSound(enableInteraction)
                                .tvRemoteClick { openOverlaySettings() }
                                .clickable(
                                    interactionSource = enableInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        openOverlaySettings()
                                    }
                                )
                                .focusRequester(enableFocusRequester)
                                .focusable(interactionSource = enableInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Enable Permission",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val laterInteraction = remember { MutableInteractionSource() }
                        val laterFocused by laterInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .scale(if (laterFocused) 1.02f else 1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (laterFocused) TvBgSubtle else TvBgSurface)
                                .border(
                                    1.dp,
                                    if (laterFocused) TvBorderFocused else TvBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .tvFocusSound(laterInteraction)
                                .tvRemoteClick(onDismiss)
                                .clickable(
                                    interactionSource = laterInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onDismiss()
                                    }
                                )
                                .focusable(interactionSource = laterInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Maybe Later",
                                color = if (laterFocused) TvTextMain else TvTextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
