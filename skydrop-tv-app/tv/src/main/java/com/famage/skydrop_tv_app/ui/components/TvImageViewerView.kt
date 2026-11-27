package com.famage.skydrop_tv_app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.ui.theme.TvTextMain
import com.famage.skydrop_tv_app.ui.theme.TvTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TV Image viewer with auto-hiding overlay action bar and remote controls.
 */
@Composable
fun TvImageViewerView(
    file: TvReceivedFile,
    targetFile: File,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val closeRequester = remember { FocusRequester() }
    val hiddenRequester = remember { FocusRequester() }

    LaunchedEffect(targetFile) {
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(targetFile.absolutePath, opts)
                var sampleSize = 1
                while ((opts.outWidth / sampleSize) > 1920 || (opts.outHeight / sampleSize) > 1080) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                bitmap = BitmapFactory.decodeFile(targetFile.absolutePath, decodeOpts)
            } catch (_: Exception) {}
        }
    }

    // Auto-hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls, lastInteractionTime) {
        if (showControls) {
            delay(4000L)
            showControls = false
            hiddenRequester.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        delay(200)
        closeRequester.requestFocus()
    }

    fun wakeControls() {
        lastInteractionTime = System.currentTimeMillis()
        if (!showControls) {
            showControls = true
            closeRequester.requestFocus()
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
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN,
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            wakeControls()
                            false
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
        // Fullscreen Image View
        Box(
            modifier = Modifier.fillMaxSize().padding(if (showControls) 32.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (showControls) 12.dp else 0.dp))
                )
            } else {
                Text("Loading image...", color = TvTextSecondary, fontSize = 16.sp)
            }
        }

        // Overlay Action Bar
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(file.name, color = TvTextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("From: ${file.senderName} • Image Preview", color = TvTextSecondary, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton("Open External", Icons.Default.Launch) {
                        lastInteractionTime = System.currentTimeMillis()
                        openInExternalApp(context, file)
                    }
                    TvActionButton("Close", Icons.Default.Close, modifier = Modifier.focusRequester(closeRequester)) {
                        onDismiss()
                    }
                }
            }
        }
    }
}
