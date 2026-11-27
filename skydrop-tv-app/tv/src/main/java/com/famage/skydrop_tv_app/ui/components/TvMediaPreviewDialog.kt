package com.famage.skydrop_tv_app.ui.components

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun TvMediaPreviewDialog(
    file: TvReceivedFile,
    onDismiss: () -> Unit
) {
    val targetFile = remember(file.filePath) { File(file.filePath) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                        onDismiss()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                TvMediaClassifier.isVideo(file) -> {
                    TvVideoPlayerView(file, targetFile, onDismiss)
                }
                TvMediaClassifier.isImage(file) -> {
                    TvImageViewerView(file, targetFile, onDismiss)
                }
                TvMediaClassifier.isAudio(file) -> {
                    TvAudioPlayerView(file, targetFile, onDismiss)
                }
                TvMediaClassifier.isText(file) -> {
                    TvTextViewerView(file, targetFile, onDismiss)
                }
                else -> {
                    TvDocumentFallbackView(file, targetFile, onDismiss)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvAudioPlayerView(
    file: TvReceivedFile,
    targetFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uri = remember(targetFile) { Uri.fromFile(targetFile) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    val playPauseRequester = remember { FocusRequester() }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(500L)
        }
    }

    LaunchedEffect(Unit) {
        delay(200)
        playPauseRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000L).coerceAtLeast(0L))
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration))
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(TvBgElevated)
                .border(1.dp, TvBorder, RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE040FB).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color(0xFFE040FB), modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(file.name, color = TvTextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("From: ${file.senderName}", color = TvTextSecondary, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(24.dp))

            // Progress bar
            val progress = if (duration > 0) (currentPos.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(SkyBlue)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDurationMs(currentPos), color = TvTextSecondary, fontSize = 12.sp)
                Text(formatDurationMs(duration), color = TvTextSecondary, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Play / Pause controls
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCircleControl(Icons.Default.Replay10, "Rewind 10s") {
                    exoPlayer.seekTo((exoPlayer.currentPosition - 10000L).coerceAtLeast(0L))
                }
                TvCircleControl(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.focusRequester(playPauseRequester)
                ) {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
                TvCircleControl(Icons.Default.Forward10, "Forward 10s") {
                    exoPlayer.seekTo((exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvActionButton("Open External", Icons.Default.Launch) {
                    openInExternalApp(context, file)
                }
                TvActionButton("Close", Icons.Default.Close) {
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun TvTextViewerView(
    file: TvReceivedFile,
    targetFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var textContent by remember { mutableStateOf("Loading file content...") }
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(targetFile) {
        withContext(Dispatchers.IO) {
            try {
                if (targetFile.exists()) {
                    val maxBytes = 40000
                    val bytes = targetFile.inputStream().use { it.readNBytes(maxBytes) }
                    textContent = String(bytes, Charsets.UTF_8)
                    if (targetFile.length() > maxBytes) {
                        textContent += "\n\n--- [Preview truncated, open in external app for full file] ---"
                    }
                } else {
                    textContent = "File not found on disk"
                }
            } catch (e: Exception) {
                textContent = "Error reading file: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(200)
        closeRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(TvBgElevated)
            .border(1.dp, TvBorder, RoundedCornerShape(20.dp))
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(file.name, color = TvTextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("From: ${file.senderName} • Text Document", color = TvTextSecondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton("Open External", Icons.Default.Launch) {
                    openInExternalApp(context, file)
                }
                TvActionButton("Close", Icons.Default.Close, modifier = Modifier.focusRequester(closeRequester)) {
                    onDismiss()
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0F172A))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = textContent,
                color = TvTextMain,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun TvDocumentFallbackView(
    file: TvReceivedFile,
    targetFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val openRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        openRequester.requestFocus()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(520.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(TvBgElevated)
            .border(1.dp, TvBorder, RoundedCornerShape(24.dp))
            .padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(SkyBlue.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.InsertDriveFile, null, tint = SkyBlue, modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(file.name, color = TvTextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text("From: ${file.senderName} • ${file.mimeType.ifBlank { "Document" }}", color = TvTextSecondary, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E293B))
                .padding(14.dp)
        ) {
            Column {
                Text("Saved at:", color = TvTextMuted, fontSize = 11.sp)
                Text(file.filePath, color = TvTextMain, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvActionButton(
                "Open with App",
                Icons.Default.Launch,
                modifier = Modifier.focusRequester(openRequester)
            ) {
                openInExternalApp(context, file)
            }
            TvActionButton("Close", Icons.Default.Close) {
                onDismiss()
            }
        }
    }
}
