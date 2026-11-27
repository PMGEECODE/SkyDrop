package com.famage.skydrop_tv_app.ui.screens

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvHistoryRecord
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.receiver.TvStorageManager
import com.famage.skydrop_tv_app.ui.components.TvMediaPreviewDialog
import com.famage.skydrop_tv_app.ui.components.tvFocusSound
import com.famage.skydrop_tv_app.ui.components.tvRemoteClick
import com.famage.skydrop_tv_app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TvTransferHistoryScreen(
    historyItems: List<TvHistoryRecord> = emptyList(),
    receivedFiles: List<TvReceivedFile> = emptyList(),
    modifier: Modifier = Modifier
) {
    var previewFile by remember { mutableStateOf<TvReceivedFile?>(null) }

    if (previewFile != null) {
        TvMediaPreviewDialog(
            file = previewFile!!,
            onDismiss = { previewFile = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 8.dp)
    ) {
        Text(
            text = "TRANSFER HISTORY LOG (${historyItems.size})",
            color = TvTextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(TvBgSurface)
                    .border(1.dp, TvBorder, RoundedCornerShape(18.dp))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(TvBgElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = "No Transfer History",
                        color = TvTextMain,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Completed and incoming peer transfers will be recorded and audited here.",
                        color = TvTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(historyItems) { item ->
                    TvHistoryItemCard(
                        item = item,
                        onClick = {
                            if (item.isSuccessful) {
                                val match = receivedFiles.firstOrNull { it.name == item.fileName }
                                val toPreview = match ?: TvReceivedFile(
                                    id = item.id,
                                    name = item.fileName,
                                    sizeBytes = item.sizeBytes,
                                    mimeType = TvStorageManager.getMimeType(item.fileName),
                                    senderName = item.peerName,
                                    receivedAt = item.timestamp,
                                    filePath = "/storage/emulated/0/Download/SkyDrop/${item.fileName}"
                                )
                                previewFile = toPreview
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvHistoryItemCard(
    item: TvHistoryRecord,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val timeString = remember(item.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) TvBgElevated else TvBgSurface)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) TvBorderFocused else TvBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick {
                onClick()
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = if (item.isSuccessful) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (item.isSuccessful) SuccessGreen else DangerRed,
                modifier = Modifier.size(22.dp)
            )

            Column {
                Text(
                    text = item.fileName,
                    color = TvTextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "From: ${item.peerName} • $timeString",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Text(
            text = if (item.isSuccessful) "COMPLETED" else "FAILED",
            color = if (item.isSuccessful) SuccessGreen else DangerRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}
