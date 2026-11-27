package com.famage.skydrop_tv_app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.model.TvReceivedFile
import com.famage.skydrop_tv_app.ui.theme.*
import java.io.File

object TvMediaClassifier {
    private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "webm", "ts", "3gp", "m4v", "flv")
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
    private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus")
    private val TEXT_EXTS = setOf("txt", "json", "csv", "log", "srt", "vtt", "md", "xml")

    fun isVideo(file: TvReceivedFile): Boolean {
        val mime = file.mimeType.lowercase()
        if (mime.startsWith("video/")) return true
        val ext = File(file.name).extension.lowercase()
        return ext in VIDEO_EXTS
    }

    fun isImage(file: TvReceivedFile): Boolean {
        val mime = file.mimeType.lowercase()
        if (mime.startsWith("image/")) return true
        val ext = File(file.name).extension.lowercase()
        return ext in IMAGE_EXTS
    }

    fun isAudio(file: TvReceivedFile): Boolean {
        val mime = file.mimeType.lowercase()
        if (mime.startsWith("audio/")) return true
        val ext = File(file.name).extension.lowercase()
        return ext in AUDIO_EXTS
    }

    fun isText(file: TvReceivedFile): Boolean {
        val mime = file.mimeType.lowercase()
        if (mime.startsWith("text/")) return true
        val ext = File(file.name).extension.lowercase()
        return ext in TEXT_EXTS
    }
}

fun openInExternalApp(context: Context, file: TvReceivedFile) {
    try {
        val targetFile = File(file.filePath)
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )
        } catch (_: Exception) {
            Uri.fromFile(targetFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open ${file.name}", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TvActionButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val view = LocalView.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) SkyBlue else TvBgSurface)
            .border(1.dp, if (isFocused) SkyBlue else TvBorder, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = if (isFocused) Color.White else TvTextMain, modifier = Modifier.size(16.dp))
            Text(text, color = if (isFocused) Color.White else TvTextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TvCircleControl(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val view = LocalView.current

    Box(
        modifier = modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(if (isFocused) SkyBlue else Color.White.copy(alpha = 0.15f))
            .border(2.dp, if (isFocused) SkyBlue else Color.Transparent, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (isFocused) Color.White else TvTextMain,
            modifier = Modifier.size(28.dp)
        )
    }
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
