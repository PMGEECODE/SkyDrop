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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.IncomingPairRequest
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.delay

/**
 * TV Modal Dialog displayed when an incoming pairing request is received
 * from a phone, laptop, or desktop over the local network.
 */
@Composable
fun TvIncomingPairDialog(
    request: IncomingPairRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val acceptFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            acceptFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = modifier
                    .widthIn(max = 580.dp)
                    .fillMaxWidth(0.75f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvBgElevated)
                    .border(1.5.dp, TvBorder, RoundedCornerShape(24.dp))
                    .padding(28.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(SkyBlue.copy(alpha = 0.15f))
                            .border(1.5.dp, SkyBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Pairing Request",
                            color = TvTextMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${request.deviceName} wants to pair with this TV.",
                            color = TvTextMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = "Accepting will display the 6-digit PIN and QR code on this screen so the remote device can connect.",
                            color = TvTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val acceptInteraction = remember { MutableInteractionSource() }
                        val acceptFocused by acceptInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                                .scale(if (acceptFocused) 1.03f else 1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (acceptFocused) SkyBlueDark else SkyBlue)
                                .border(
                                    1.5.dp,
                                    if (acceptFocused) Color.White else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .tvFocusSound(acceptInteraction)
                                .tvRemoteClick(onAccept)
                                .clickable(
                                    interactionSource = acceptInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onAccept()
                                    }
                                )
                                .focusRequester(acceptFocusRequester)
                                .focusable(interactionSource = acceptInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Accept & Show QR",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val declineInteraction = remember { MutableInteractionSource() }
                        val declineFocused by declineInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .scale(if (declineFocused) 1.02f else 1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (declineFocused) DangerRed.copy(alpha = 0.25f) else TvBgSurface)
                                .border(
                                    1.dp,
                                    if (declineFocused) DangerRed else TvBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .tvFocusSound(declineInteraction)
                                .tvRemoteClick(onDecline)
                                .clickable(
                                    interactionSource = declineInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onDecline()
                                    }
                                )
                                .focusable(interactionSource = declineInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Decline",
                                color = if (declineFocused) DangerRed else TvTextSecondary,
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
