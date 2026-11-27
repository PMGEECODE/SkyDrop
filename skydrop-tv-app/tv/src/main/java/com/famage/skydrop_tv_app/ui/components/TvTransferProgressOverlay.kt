package com.famage.skydrop_tv_app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.receiver.TvReceiveProgress
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Compact, unobtrusive progress window positioned at the bottom right of the TV screen
 * to show real-time incoming file transfer progress, bytes and completion state.
 */
@Composable
fun TvTransferProgressOverlay(
    serviceProgress: TvReceiveProgress?,
    completionMessage: String?,
    modifier: Modifier = Modifier
) {
    val isTransferring = serviceProgress != null
    val isVisible = isTransferring || !completionMessage.isNullOrBlank()

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D1322).copy(alpha = 0.95f))
                .border(
                    width = 1.5.dp,
                    color = if (isTransferring) SkyBlue else SuccessGreen,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            if (isTransferring && serviceProgress != null) {
                val pct = serviceProgress.percent
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { if (pct >= 0) pct / 100f else 0.5f },
                            modifier = Modifier.size(26.dp),
                            color = SkyBlue,
                            strokeWidth = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Receiving Drop...",
                                    color = SkyBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (pct >= 0) "$pct%" else "Streaming",
                                    color = TvTextMain,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = serviceProgress.filename,
                                color = TvTextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Linear Progress Bar
                    val fraction = if (pct >= 0) (pct / 100f).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(SkyBlue)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatBytes(serviceProgress.bytesReceived),
                            color = TvTextSecondary,
                            fontSize = 10.sp
                        )
                        if (serviceProgress.totalBytes > 0) {
                            Text(
                                text = "Total: ${formatBytes(serviceProgress.totalBytes)}",
                                color = TvTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            } else if (!completionMessage.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = completionMessage,
                        color = TvTextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = 0
    var size = bytes.toDouble()
    while (size >= 1024 && digitGroups < units.size - 1) {
        size /= 1024
        digitGroups++
    }
    return "%.1f %s".format(size, units[digitGroups])
}
