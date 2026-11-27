package com.famage.skydrop_tv_app.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.ui.theme.*
import com.famage.skydrop_tv_app.updater.TvUpdateDownloadProgress
import com.famage.skydrop_tv_app.updater.TvUpdateInfo
import kotlinx.coroutines.delay

/**
 * TV Update Dialog specifically designed for remote control D-pad navigation.
 * Displays version differences, release notes, live download progress, and focusable buttons.
 */
@Composable
fun TvUpdateDialog(
    updateInfo: TvUpdateInfo,
    downloadProgress: TvUpdateDownloadProgress?,
    isDownloading: Boolean,
    onInstallClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val installRequester = remember { FocusRequester() }
    val dismissRequester = remember { FocusRequester() }

    LaunchedEffect(isDownloading) {
        if (!isDownloading) {
            delay(150)
            installRequester.requestFocus()
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(580.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvBgElevated)
                    .border(1.5.dp, SkyBlue, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(SkyBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = SkyBlue,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "New Update Available!",
                    color = TvTextMain,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "SkyDrop v${updateInfo.latestVersion} is ready to install (current: v${updateInfo.currentVersion})",
                    color = TvTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Release Notes Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, TvBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        Text(
                            text = updateInfo.releaseTitle.ifBlank { "Release Notes" },
                            color = SkyBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = updateInfo.releaseNotes.ifBlank { "Performance improvements and bug fixes." },
                            color = TvTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Download Progress State
                if (isDownloading) {
                    val pct = downloadProgress?.percent ?: 0
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Downloading update...", color = SkyBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("$pct%", color = TvTextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(SkyBlue)
                            )
                        }
                    }
                } else {
                    // D-Pad Remote Navigable Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    ) {
                        TvUpdateDialogButton(
                            text = "Download & Install",
                            isPrimary = true,
                            modifier = Modifier.focusRequester(installRequester),
                            onClick = onInstallClick
                        )

                        TvUpdateDialogButton(
                            text = "Later",
                            isPrimary = false,
                            modifier = Modifier.focusRequester(dismissRequester),
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvUpdateDialogButton(
    text: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = when {
        isFocused -> SkyBlue
        isPrimary -> SkyBlue.copy(alpha = 0.25f)
        else -> TvBgSurface
    }
    val border = when {
        isFocused -> SkyBlue
        isPrimary -> SkyBlue
        else -> TvBorder
    }
    val textColor = when {
        isFocused -> Color.White
        isPrimary -> SkyBlue
        else -> TvTextMain
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
