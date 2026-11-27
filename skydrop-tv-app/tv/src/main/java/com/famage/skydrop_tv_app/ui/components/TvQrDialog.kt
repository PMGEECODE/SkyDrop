package com.famage.skydrop_tv_app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.security.TvPairingManager
import com.famage.skydrop_tv_app.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.util.EnumMap

@Composable
fun TvQrDialog(
    onDismiss: () -> Unit,
    pin: String = "",
    tvName: String = "",
    ipAddress: String = "",
    port: Int = 8444,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val effectiveTvName = remember(tvName) {
        if (tvName.isNotBlank()) tvName else TvIdentityManager.getDeviceName()
    }
    val effectiveIp = remember(ipAddress) {
        if (ipAddress.isNotBlank()) ipAddress.substringBefore(":") else TvIdentityManager.getLocalIpAddress()
    }
    val effectiveDeviceId = remember {
        TvIdentityManager.getDeviceId(context)
    }
    val effectivePin = remember(pin) {
        if (pin.isNotBlank()) pin else TvPairingManager.getActivePin()
    }

    val qrContent = remember(effectiveIp, port, effectiveTvName, effectiveDeviceId, effectivePin) {
        TvPairingManager.buildPairingQrUri(
            host = effectiveIp,
            port = port,
            deviceName = effectiveTvName,
            deviceId = effectiveDeviceId,
            secret = effectivePin
        )
    }

    val qrBitmap = remember(qrContent) {
        generateTvQrBitmap(qrContent, 512, 512)
    }

    var pairedDeviceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(effectivePin) {
        TvPairingManager.pairSuccessEvents.collect { peer ->
            pairedDeviceName = peer.name
            delay(1800)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (pairedDeviceName == null) onDismiss()
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
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvBgElevated)
                    .border(1.5.dp, TvBorder, RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                AnimatedContent(
                    targetState = pairedDeviceName,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.9f))
                            .togetherWith(fadeOut(animationSpec = tween(200)))
                    },
                    label = "tv_pair_dialog_state"
                ) { successName ->
                    if (successName != null) {
                        TvPairSuccessView(deviceName = successName)
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Header Row
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
                                            .background(SkyBlue.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QrCode,
                                            contentDescription = null,
                                            tint = SkyBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "My SkyDrop QR Code",
                                            color = TvTextMain,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Pair mobile or computer with this TV",
                                            color = TvTextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                val closeInteraction = remember { MutableInteractionSource() }
                                val closeFocused by closeInteraction.collectIsFocusedAsState()

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (closeFocused) TvCardHover else TvCardBg)
                                        .border(
                                            1.dp,
                                            if (closeFocused) SkyBlue else TvBorder,
                                            CircleShape
                                        )
                                        .tvFocusSound(closeInteraction)
                                        .tvRemoteClick(onDismiss)
                                        .focusable(interactionSource = closeInteraction),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = if (closeFocused) SkyBlue else TvTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Main Content Columns
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(36.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Real QR Code
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(240.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color.White)
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (qrBitmap != null) {
                                            Image(
                                                bitmap = qrBitmap.asImageBitmap(),
                                                contentDescription = "Pairing QR Code",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Text(
                                                text = "Generating QR...",
                                                color = Color.Black,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = "Ready to scan on local Wi-Fi",
                                        color = TealAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                // Right: PIN and Details
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "6-DIGIT PAIRING PIN",
                                        color = TvTextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp
                                    )

                                    Text(
                                        text = effectivePin,
                                        color = SkyBlue,
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 8.sp
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(TvCardBg)
                                            .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
                                            .padding(14.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "TV Name: $effectiveTvName",
                                                color = TvTextMain,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Local Address: $effectiveIp",
                                                color = TvTextSecondary,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }

                                    Text(
                                        text = "Scan this QR code from your phone's SkyDrop camera, or enter the PIN manually from another device.",
                                        color = TvTextMuted,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    val doneInteraction = remember { MutableInteractionSource() }
                                    val doneFocused by doneInteraction.collectIsFocusedAsState()

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .scale(if (doneFocused) 1.02f else 1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (doneFocused) SkyBlueDark else SkyBlue)
                                            .border(
                                                1.5.dp,
                                                if (doneFocused) Color.White else Color.Transparent,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .tvFocusSound(doneInteraction)
                                            .tvRemoteClick(onDismiss)
                                            .focusable(interactionSource = doneInteraction),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Done",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPairSuccessView(deviceName: String) {
    var animateCheck by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animateCheck = true
    }

    val scale by animateFloatAsState(
        targetValue = if (animateCheck) 1f else 0.35f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "tv_success_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(TealAccent.copy(alpha = 0.2f))
                .border(2.dp, TealAccent, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TealAccent,
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Paired Successfully!",
            color = TvTextMain,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = deviceName,
            color = SkyBlue,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Device verified and connected. Ready for high-speed drops.",
            color = TvTextSecondary,
            fontSize = 13.sp
        )
    }
}

fun generateTvQrBitmap(content: String, width: Int, height: Int): Bitmap? {
    return try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 1)
        }
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}
