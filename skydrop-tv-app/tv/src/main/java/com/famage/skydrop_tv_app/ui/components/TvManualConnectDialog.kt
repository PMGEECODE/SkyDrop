package com.famage.skydrop_tv_app.ui.components

import android.view.SoundEffectConstants
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.model.PeerStatus
import com.famage.skydrop_tv_app.security.TvPairedDeviceStore
import com.famage.skydrop_tv_app.security.TvPairingManager
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class OutboundPairStep {
    INITIAL,        // Ready to send request or enter info
    WAITING_ACCEPT, // Request sent; waiting for remote user to tap Accept
    ENTER_PIN,      // Remote accepted and is displaying PIN/QR; TV enters 6 digits
    PAIRING,        // Verifying PIN and exchanging auth tokens
    SUCCESS         // Paired successfully
}

@Composable
fun TvManualConnectDialog(
    onDismiss: () -> Unit,
    onConnected: (PeerStatus) -> Unit = {},
    initialHost: String = "",
    initialPort: String = "8443",
    initialTls: Boolean = true,
    autoStart: Boolean = initialHost.isNotBlank(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val pairedStore = remember { TvPairedDeviceStore.getInstance(context) }

    var targetHost by remember(initialHost) { mutableStateOf(initialHost) }
    var targetPort by remember(initialPort) { mutableStateOf(initialPort) }
    var targetPin by remember { mutableStateOf("") }
    var isTls by remember(initialTls) { mutableStateOf(initialTls) }

    var step by remember {
        mutableStateOf(OutboundPairStep.INITIAL)
    }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val pinFocusRequester = remember { FocusRequester() }

    fun sendPairRequest() {
        if (targetHost.isBlank()) return
        val portInt = targetPort.toIntOrNull() ?: if (isTls) 8443 else 8444
        val host = targetHost.trim()
        val tvName = TvIdentityManager.getDeviceName()
        val tvDeviceId = TvIdentityManager.getDeviceId(context)

        step = OutboundPairStep.WAITING_ACCEPT
        isError = false
        statusMessage = "Connection request sent. Waiting for approval on $host..."

        coroutineScope.launch {
            val res = TvPairingManager.requestPairing(
                host = host,
                port = portInt,
                isTls = isTls,
                localDeviceName = tvName,
                localDeviceId = tvDeviceId,
                onStatusUpdate = { msg -> statusMessage = msg }
            )

            if (res.isSuccess) {
                step = OutboundPairStep.ENTER_PIN
                statusMessage = "Request accepted! Enter the 6-digit PIN displayed on the remote device."
                delay(300)
                try {
                    pinFocusRequester.requestFocus()
                } catch (_: Exception) {}
            } else {
                step = OutboundPairStep.INITIAL
                isError = true
                statusMessage = res.exceptionOrNull()?.message ?: "Connection request failed"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (autoStart && initialHost.isNotBlank()) {
            delay(150)
            sendPairRequest()
        }
    }

    fun confirmPinAndPair() {
        if (targetHost.isBlank() || targetPin.isBlank()) return
        val portInt = targetPort.toIntOrNull() ?: if (isTls) 8443 else 8444
        val host = targetHost.trim()
        val secret = targetPin.trim()
        val tvName = TvIdentityManager.getDeviceName()
        val tvDeviceId = TvIdentityManager.getDeviceId(context)

        step = OutboundPairStep.PAIRING
        isError = false
        statusMessage = "Verifying PIN and establishing secure connection..."

        coroutineScope.launch {
            val res = TvPairingManager.pairWithDevice(
                host = host,
                port = portInt,
                secret = secret,
                localDeviceName = tvName,
                localDeviceId = tvDeviceId,
                isTls = isTls
            )

            if (res.isSuccess) {
                val peer = res.getOrNull()!!
                pairedStore.saveDevice(peer)
                step = OutboundPairStep.SUCCESS
                statusMessage = "Paired with ${peer.name} ($host:$portInt)!"
                onConnected(peer)
                delay(1400)
                onDismiss()
            } else {
                step = OutboundPairStep.ENTER_PIN
                isError = true
                statusMessage = res.exceptionOrNull()?.message ?: "PIN verification failed"
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (step != OutboundPairStep.PAIRING) onDismiss()
        },
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
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvBgElevated)
                    .border(1.5.dp, TvBorder, RoundedCornerShape(24.dp))
                    .padding(28.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(TealAccent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = TealAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (step == OutboundPairStep.ENTER_PIN) "Enter 6-Digit PIN" else "Connect Device",
                                    color = TvTextMain,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (step) {
                                        OutboundPairStep.WAITING_ACCEPT -> "Waiting for peer to approve pairing..."
                                        OutboundPairStep.ENTER_PIN -> "Type the PIN displayed on the other device"
                                        OutboundPairStep.PAIRING -> "Securing connection..."
                                        OutboundPairStep.SUCCESS -> "Connected!"
                                        else -> "Send a connection request to pair securely"
                                    },
                                    color = TvTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        val closeInteraction = remember { MutableInteractionSource() }
                        val closeFocused by closeInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (closeFocused) TvCardHover else TvCardBg)
                                .border(1.dp, if (closeFocused) SkyBlue else TvBorder, CircleShape)
                                .tvFocusSound(closeInteraction)
                                .tvRemoteClick(onDismiss)
                                .clickable(
                                    interactionSource = closeInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onDismiss()
                                    }
                                )
                                .focusable(interactionSource = closeInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = if (closeFocused) SkyBlue else TvTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (step == OutboundPairStep.INITIAL || step == OutboundPairStep.WAITING_ACCEPT) {
                        // Preset Mode Chips (Desktop vs Mobile)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val isDesktopSelected = targetPort == "8443" && isTls
                            val deskInteraction = remember { MutableInteractionSource() }
                            val deskFocused by deskInteraction.collectIsFocusedAsState()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .scale(if (deskFocused) 1.02f else 1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isDesktopSelected) SkyBlue.copy(alpha = 0.2f) else TvCardBg)
                                    .border(
                                        1.dp,
                                        if (deskFocused) SkyBlue else if (isDesktopSelected) SkyBlue.copy(alpha = 0.6f) else TvBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .tvFocusSound(deskInteraction)
                                    .tvRemoteClick {
                                        targetPort = "8443"
                                        isTls = true
                                    }
                                    .clickable(
                                        interactionSource = deskInteraction,
                                        indication = null,
                                        onClick = {
                                            view.playSoundEffect(SoundEffectConstants.CLICK)
                                            targetPort = "8443"
                                            isTls = true
                                        }
                                    )
                                    .focusable(interactionSource = deskInteraction),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "💻 Desktop Daemon (8443 TLS)",
                                    color = if (isDesktopSelected) SkyBlue else TvTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isDesktopSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }

                            val isMobileSelected = targetPort == "8444" && !isTls
                            val mobInteraction = remember { MutableInteractionSource() }
                            val mobFocused by mobInteraction.collectIsFocusedAsState()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .scale(if (mobFocused) 1.02f else 1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isMobileSelected) TealAccent.copy(alpha = 0.2f) else TvCardBg)
                                    .border(
                                        1.dp,
                                        if (mobFocused) TealAccent else if (isMobileSelected) TealAccent.copy(alpha = 0.6f) else TvBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .tvFocusSound(mobInteraction)
                                    .tvRemoteClick {
                                        targetPort = "8444"
                                        isTls = false
                                    }
                                    .clickable(
                                        interactionSource = mobInteraction,
                                        indication = null,
                                        onClick = {
                                            view.playSoundEffect(SoundEffectConstants.CLICK)
                                            targetPort = "8444"
                                            isTls = false
                                        }
                                    )
                                    .focusable(interactionSource = mobInteraction),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "📱 Mobile / TV (8444 Clear)",
                                    color = if (isMobileSelected) TealAccent else TvTextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isMobileSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        // Input: Host IP Address & Port
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(2f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Target Device IP Address",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedTextField(
                                    value = targetHost,
                                    onValueChange = { targetHost = it.trim() },
                                    placeholder = { Text("e.g. 192.168.74.21", color = TvTextMuted, fontSize = 13.sp) },
                                    singleLine = true,
                                    enabled = step == OutboundPairStep.INITIAL,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TvTextMain,
                                        unfocusedTextColor = TvTextMain,
                                        focusedBorderColor = SkyBlue,
                                        unfocusedBorderColor = TvBorder,
                                        focusedContainerColor = TvCardBg,
                                        unfocusedContainerColor = TvCardBg
                                    )
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Port",
                                    color = TvTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedTextField(
                                    value = targetPort,
                                    onValueChange = { targetPort = it.trim() },
                                    singleLine = true,
                                    enabled = step == OutboundPairStep.INITIAL,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TvTextMain,
                                        unfocusedTextColor = TvTextMain,
                                        focusedBorderColor = SkyBlue,
                                        unfocusedBorderColor = TvBorder,
                                        focusedContainerColor = TvCardBg,
                                        unfocusedContainerColor = TvCardBg
                                    )
                                )
                            }
                        }
                    } else if (step == OutboundPairStep.ENTER_PIN || step == OutboundPairStep.PAIRING || step == OutboundPairStep.SUCCESS) {
                        // Enter PIN Screen (displayed after remote device accepted)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Enter 6-Digit PIN from $targetHost",
                                color = TvTextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = targetPin,
                                onValueChange = {
                                    if (it.length <= 6) {
                                        targetPin = it.trim()
                                        if (it.length == 6) {
                                            confirmPinAndPair()
                                        }
                                    }
                                },
                                placeholder = { Text("e.g. 123456", color = TvTextMuted, fontSize = 16.sp) },
                                singleLine = true,
                                enabled = step == OutboundPairStep.ENTER_PIN,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(pinFocusRequester),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TvTextMain,
                                    unfocusedTextColor = TvTextMain,
                                    focusedBorderColor = SkyBlue,
                                    unfocusedBorderColor = TvBorder,
                                    focusedContainerColor = TvCardBg,
                                    unfocusedContainerColor = TvCardBg
                                )
                            )
                        }
                    }

                    // Status / Feedback Banner
                    AnimatedVisibility(visible = statusMessage != null) {
                        statusMessage?.let { msg ->
                            val bgColor = when {
                                step == OutboundPairStep.SUCCESS -> TealAccent.copy(alpha = 0.15f)
                                isError -> Color(0xFFEF4444).copy(alpha = 0.15f)
                                else -> SkyBlue.copy(alpha = 0.15f)
                            }
                            val textColor = when {
                                step == OutboundPairStep.SUCCESS -> TealAccent
                                isError -> Color(0xFFF87171)
                                else -> SkyBlue
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (step == OutboundPairStep.WAITING_ACCEPT || step == OutboundPairStep.PAIRING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = textColor
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (step == OutboundPairStep.SUCCESS) Icons.Default.CheckCircle else if (isError) Icons.Default.Error else Icons.Default.Info,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = msg,
                                    color = textColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val cancelInteraction = remember { MutableInteractionSource() }
                        val cancelFocused by cancelInteraction.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .scale(if (cancelFocused) 1.02f else 1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (cancelFocused) TvCardHover else TvCardBg)
                                .border(1.dp, if (cancelFocused) SkyBlue else TvBorder, RoundedCornerShape(12.dp))
                                .tvFocusSound(cancelInteraction)
                                .tvRemoteClick(onDismiss)
                                .clickable(
                                    interactionSource = cancelInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onDismiss()
                                    }
                                )
                                .focusable(interactionSource = cancelInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Cancel",
                                color = TvTextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val canSendReq = targetHost.isNotBlank() && step == OutboundPairStep.INITIAL
                        val canConfirmPin = targetPin.length == 6 && step == OutboundPairStep.ENTER_PIN

                        val primaryInteraction = remember { MutableInteractionSource() }
                        val primaryFocused by primaryInteraction.collectIsFocusedAsState()

                        val isEnabled = canSendReq || canConfirmPin
                        val btnLabel = when (step) {
                            OutboundPairStep.WAITING_ACCEPT -> "Waiting for Approval..."
                            OutboundPairStep.ENTER_PIN -> "Confirm & Pair"
                            OutboundPairStep.PAIRING -> "Connecting..."
                            OutboundPairStep.SUCCESS -> "Paired!"
                            else -> "Send Pair Request"
                        }

                        val onPrimaryClick = {
                            if (canSendReq) {
                                sendPairRequest()
                            } else if (canConfirmPin) {
                                confirmPinAndPair()
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1.5f)
                                .height(44.dp)
                                .scale(if (primaryFocused && isEnabled) 1.02f else 1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (!isEnabled) SkyBlue.copy(alpha = 0.4f)
                                    else if (primaryFocused) SkyBlueDark
                                    else SkyBlue
                                )
                                .border(
                                    1.5.dp,
                                    if (primaryFocused && isEnabled) Color.White else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .tvFocusSound(primaryInteraction)
                                .tvRemoteClick(onPrimaryClick)
                                .clickable(
                                    interactionSource = primaryInteraction,
                                    indication = null,
                                    onClick = {
                                        view.playSoundEffect(SoundEffectConstants.CLICK)
                                        onPrimaryClick()
                                    }
                                )
                                .focusable(interactionSource = primaryInteraction),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = btnLabel,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
