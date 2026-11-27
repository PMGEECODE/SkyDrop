package com.famage.skydrop_tv_app.ui.components

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Appearance settings card matching the mobile SkyDrop app with 3 theme options.
 */
@Composable
fun TvAppearanceCard(
    currentPreference: ThemePreference,
    onPreferenceChange: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TvBgSurface)
            .border(1.dp, TvBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = null,
                tint = SkyBlue,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Appearance",
                    color = TvTextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose your preferred color theme",
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeOptionButton(
                icon = Icons.Default.Brightness4,
                label = "System",
                isSelected = currentPreference == ThemePreference.SYSTEM,
                onClick = { onPreferenceChange(ThemePreference.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionButton(
                icon = Icons.Default.LightMode,
                label = "Light",
                isSelected = currentPreference == ThemePreference.LIGHT,
                onClick = { onPreferenceChange(ThemePreference.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionButton(
                icon = Icons.Default.DarkMode,
                label = "Dark",
                isSelected = currentPreference == ThemePreference.DARK,
                onClick = { onPreferenceChange(ThemePreference.DARK) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeOptionButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bg = when {
        isFocused -> if (isSelected) TealAccentDark.copy(alpha = 0.35f) else TvBgElevated
        isSelected -> TealAccent.copy(alpha = 0.25f)
        else -> TvBgSubtle
    }

    val borderCol = when {
        isFocused -> TvBorderFocused
        isSelected -> TealAccent
        else -> TvBorder
    }

    val contentColor = when {
        isFocused || isSelected -> TvTextMain
        else -> TvTextSecondary
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .scale(if (isFocused) 1.03f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = borderCol,
                shape = RoundedCornerShape(10.dp)
            )
            .tvFocusSound(interactionSource)
            .tvRemoteClick(onClick = onClick)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    onClick()
                }
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) TealAccentDark else contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
