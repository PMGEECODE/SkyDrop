package com.famage.skydrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famage.skydrop.model.PeerStatus

import com.famage.skydrop.presence.PresenceConstants
import com.famage.skydrop.presence.PresenceState

@Composable
fun DeviceCard(
    peer: PeerStatus,
    enabled: Boolean,
    queuedCount: Int = 0,
    onSendClick: () -> Unit,
    onPairClick: () -> Unit,
    onUnpairClick: (() -> Unit)? = null,
    onRetryClick: (() -> Unit)? = null
) {
    val isReadyToSend = peer.isPaired && peer.canSend

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled && (isReadyToSend || !peer.isPaired),
                onClick = if (peer.isPaired) onSendClick else onPairClick
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReadyToSend) {
                MaterialTheme.colorScheme.surface
            } else if (peer.isPaired && !peer.canSend) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = when {
                        !peer.isPaired -> MaterialTheme.colorScheme.primary
                        peer.presenceState == PresenceState.ONLINE -> Color(0xFF2E7D32)
                        peer.presenceState == PresenceState.BUSY -> Color(0xFF1976D2)
                        peer.presenceState == PresenceState.DEGRADED -> Color(0xFFFFA000)
                        else -> Color(0xFFD32F2F)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    val (badgeText, badgeColor, textColor) = when {
                        !peer.isPaired -> Triple(
                            "Ready to Pair",
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        peer.presenceState == PresenceState.ONLINE -> Triple(
                            if (peer.latencyMs > 0) "ONLINE • ${peer.latencyMs}ms" else "ONLINE",
                            Color(0xFFE8F5E9),
                            Color(0xFF1B5E20)
                        )
                        peer.presenceState == PresenceState.BUSY -> Triple(
                            "BUSY • Transferring",
                            Color(0xFFE3F2FD),
                            Color(0xFF0D47A1)
                        )
                        peer.presenceState == PresenceState.DEGRADED -> Triple(
                            "DEGRADED • Retrying",
                            Color(0xFFFFF8E1),
                            Color(0xFFE65100)
                        )
                        peer.presenceState == PresenceState.ERROR -> Triple(
                            if (peer.errorCode == PresenceConstants.ERR_AUTH_FAILED || peer.errorCode == "ERR_TOKEN_REVOKED") "TOKEN REVOKED" else "ERROR",
                            Color(0xFFFFEBEE),
                            Color(0xFFB71C1C)
                        )
                        peer.presenceState == PresenceState.OFFLINE -> Triple(
                            "OFFLINE",
                            Color(0xFFECEFF1),
                            Color(0xFF546E7A)
                        )
                        else -> Triple(
                            if (peer.isConnected) "ONLINE" else "CHECKING",
                            Color(0xFFEDE7F6),
                            Color(0xFF4A148C)
                        )
                    }

                    Surface(
                        color = badgeColor,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = textColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                val subtitle = if (peer.canSend) {
                    "${peer.host}:${peer.port} • ${if (peer.isTls) "HTTPS" else "HTTP"}"
                } else if (!peer.isPaired) {
                    "${peer.host}:${peer.port} • Ready to pair"
                } else if (!peer.errorMessage.isNullOrBlank()) {
                    "${peer.host}:${peer.port} • ${peer.errorMessage}"
                } else {
                    "${peer.host}:${peer.port} • Offline"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when {
                    peer.isPaired && peer.canSend -> {
                        Button(
                            onClick = onSendClick,
                            enabled = enabled && peer.canSend,
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        ) {
                            Text(if (queuedCount > 0) "Send ($queuedCount)" else "Drop Files")
                        }
                    }
                    peer.isPaired && !peer.canSend -> {
                        OutlinedButton(
                            onClick = { onRetryClick?.invoke() },
                            enabled = onRetryClick != null,
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        ) {
                            Text("Retry now")
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onPairClick,
                            enabled = enabled,
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        ) {
                            Text("Pair")
                        }
                    }
                }

                if (peer.isPaired && onUnpairClick != null) {
                    IconButton(
                        onClick = onUnpairClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text("✕", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
