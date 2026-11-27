package com.famage.skydrop_tv_app.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView

/**
 * TV Focus Sound Modifier:
 * Plays the native TV navigation sound whenever a component gains D-Pad focus.
 */
fun Modifier.tvFocusSound(
    interactionSource: MutableInteractionSource
): Modifier = composed {
    val view = LocalView.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    var wasFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused) {
        if (isFocused && !wasFocused) {
            view.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT)
        }
        wasFocused = isFocused
    }

    this
}

/**
 * TV Remote D-Pad Click Modifier:
 * Instantly intercepts the hardware D-Pad Center (KEYCODE_DPAD_CENTER) and ENTER keys.
 * This guarantees a SINGLE click on the TV remote directly executes the callback
 * without requiring a second click to bridge touch-delegated Compose focus.
 */
fun Modifier.tvRemoteClick(
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current

    this.onKeyEvent { keyEvent ->
        val nativeEvent = keyEvent.nativeKeyEvent
        val keyCode = nativeEvent.keyCode
        val isSelectKey = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

        if (isSelectKey && keyEvent.type == KeyEventType.KeyUp) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
            onClick()
            true
        } else if (isSelectKey && keyEvent.type == KeyEventType.KeyDown) {
            // Consume KeyDown so system doesn't swallow or delay the action
            true
        } else {
            false
        }
    }
}
