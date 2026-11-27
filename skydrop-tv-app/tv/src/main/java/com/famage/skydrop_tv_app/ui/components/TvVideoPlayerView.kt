package com.famage.skydrop_tv_app.ui.components

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.ui.theme.*
import kotlinx.coroutines.delay
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun TvVideoPlayerView(
    file: TvReceivedFile,
    targetFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uri = remember(targetFile) { Uri.fromFile(targetFile) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val playPauseRequester = remember { FocusRequester() }
    val hiddenRequester = remember { FocusRequester() }

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

    // Auto-update playback position
    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(500L)
        }
    }

    // Auto-hide controls after 4 seconds of inactivity if playing
    LaunchedEffect(showControls, isPlaying, lastInteractionTime) {
        if (showControls && isPlaying) {
            delay(4000L)
            showControls = false
            hiddenRequester.requestFocus()
        }
    }

    // Initial focus request
    LaunchedEffect(Unit) {
        delay(200)
        playPauseRequester.requestFocus()
    }

    fun wakeControls() {
        lastInteractionTime = System.currentTimeMillis()
        if (!showControls) {
            showControls = true
            playPauseRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hiddenRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                        AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!showControls) {
                                wakeControls()
                                true
                            } else {
                                lastInteractionTime = System.currentTimeMillis()
                                false
                            }
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP,
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            wakeControls()
                            false
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000L).coerceAtLeast(0L))
                            wakeControls()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration))
                            wakeControls()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            wakeControls()
                            true
                        }
                        AndroidKeyEvent.KEYCODE_BACK -> {
                            if (showControls) {
                                showControls = false
                                hiddenRequester.requestFocus()
                                true
                            } else {
                                onDismiss()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Controls & Metadata
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(32.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = file.name,
                            color = TvTextMain,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "From: ${file.senderName} • Video Preview",
                            color = TvTextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton("Open External", Icons.Default.Launch) {
                            lastInteractionTime = System.currentTimeMillis()
                            openInExternalApp(context, file)
                        }
                        TvActionButton("Close", Icons.Default.Close) {
                            onDismiss()
                        }
                    }
                }

                // Center Play/Pause button
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvCircleControl(Icons.Default.Replay10, "Rewind 10s") {
                        lastInteractionTime = System.currentTimeMillis()
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10000L).coerceAtLeast(0L))
                    }

                    TvCircleControl(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.focusRequester(playPauseRequester)
                    ) {
                        lastInteractionTime = System.currentTimeMillis()
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }

                    TvCircleControl(Icons.Default.Forward10, "Forward 10s") {
                        lastInteractionTime = System.currentTimeMillis()
                        exoPlayer.seekTo((exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration))
                    }
                }

                // Bottom Timeline
                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDurationMs(currentPos), color = TvTextMain, fontSize = 13.sp)
                        Text(formatDurationMs(duration), color = TvTextSecondary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val progress = if (duration > 0) (currentPos.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .background(SkyBlue)
                        )
                    }
                }
            }
        }
    }
}
