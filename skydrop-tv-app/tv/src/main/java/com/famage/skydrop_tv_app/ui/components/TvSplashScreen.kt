package com.famage.skydrop_tv_app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.R
import com.famage.skydrop_tv_app.ui.theme.SkyBlue
import com.famage.skydrop_tv_app.ui.theme.TvBgApp
import kotlinx.coroutines.delay

/**
 * Animated TV Splash Screen.
 * Sequence:
 * 1. Deep navy background loads instantly.
 * 2. Brand logo appears with scale and gentle fade-in.
 * 3. Animated "SkyDrop" text appears below with "Sky" in White and "Drop" in SkyBlue.
 * 4. Smoothly transitions to TvMainScreen once initialization completes.
 */
@Composable
fun TvSplashScreen(
    onSplashFinished: () -> Unit
) {
    var startAnimation by remember { mutableStateOf(false) }

    // Phase 1: Logo animation
    val logoAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "logoAlpha"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.82f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )

    // Phase 2: Text animation (staggered after logo)
    var showText by remember { mutableStateOf(false) }
    val textAlpha by animateFloatAsState(
        targetValue = if (showText) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "textAlpha"
    )
    val textOffsetY by animateFloatAsState(
        targetValue = if (showText) 0f else 18f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "textOffsetY"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(400)
        showText = true
        delay(1200)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvBgApp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated Brand Logo
            Image(
                painter = painterResource(id = R.drawable.skydrop_logo),
                contentDescription = "SkyDrop Logo",
                modifier = Modifier
                    .size(130.dp)
                    .scale(logoScale)
                    .alpha(logoAlpha)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Animated Two-Tone Brand Text: "Sky" (White) + "Drop" (SkyBlue)
            Box(
                modifier = Modifier
                    .offset(y = textOffsetY.dp)
                    .alpha(textAlpha)
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 34.sp,
                                letterSpacing = 1.sp
                            )
                        ) {
                            append("Sky")
                        }
                        withStyle(
                            style = SpanStyle(
                                color = SkyBlue,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 34.sp,
                                letterSpacing = 1.sp
                            )
                        ) {
                            append("Drop")
                        }
                    }
                )
            }
        }
    }
}
