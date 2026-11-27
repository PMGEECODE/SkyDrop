package com.famage.skydrop_tv_app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.identity.TvIdentityManager
import com.famage.skydrop_tv_app.security.TvPairingManager
import com.famage.skydrop_tv_app.ui.components.generateTvQrBitmap
import com.famage.skydrop_tv_app.ui.theme.*

@Composable
fun TvPairDeviceScreen(
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

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Real ZXing QR and PIN Display Card
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(TvBgElevated)
                .border(1.5.dp, TvBorder, RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Scan to Pair",
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

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "PAIRING PIN",
                color = TvTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Text(
                text = effectivePin,
                color = SkyBlue,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$effectiveTvName • $effectiveIp",
                color = TvTextMuted,
                fontSize = 12.sp
            )
        }

        // Right Column: Step-by-Step Instructions for TV User
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PEER AUTHORIZATION",
                color = TealAccentDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect Phone or PC to TV",
                color = TvTextMain,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(20.dp))

            TvInstructionStep(
                number = "1",
                title = "Open SkyDrop on Mobile / PC",
                desc = "Ensure your phone or computer is connected to the same Wi-Fi network."
            )

            Spacer(modifier = Modifier.height(14.dp))

            TvInstructionStep(
                number = "2",
                title = "Scan the QR Code on Screen",
                desc = "Tap 'Scan QR' in your SkyDrop app and point the camera at this TV."
            )

            Spacer(modifier = Modifier.height(14.dp))

            TvInstructionStep(
                number = "3",
                title = "Or Enter the 6-Digit PIN Manually",
                desc = "Confirm code $effectivePin to establish mutual cryptographic TLS authorization."
            )
        }
    }
}

@Composable
private fun TvInstructionStep(
    number: String,
    title: String,
    desc: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SkyBlueDark),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = TvTextMain,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Column {
            Text(
                text = title,
                color = TvTextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                color = TvTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}
