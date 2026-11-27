package com.famage.skydrop_tv_app.ui.components

import android.view.SoundEffectConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.famage.skydrop_tv_app.R
import com.famage.skydrop_tv_app.ui.navigation.TvNavItem
import com.famage.skydrop_tv_app.ui.theme.*

/**
 * Floating Pill Icon Sidebar with instant single-click TV navigation.
 */
@Composable
fun TvSidebar(
    selectedItem: TvNavItem,
    onItemSelected: (TvNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .width(60.dp)
                .wrapContentHeight()
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp), ambientColor = Color.Black)
                .clip(RoundedCornerShape(24.dp))
                .background(TvBgSidebar)
                .border(
                    width = 1.dp,
                    color = TvBorder,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top: SkyDrop Brand Icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.skydrop_logo),
                    contentDescription = "SkyDrop Logo",
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }

            Spacer(
                modifier = Modifier
                    .width(28.dp)
                    .height(1.dp)
                    .background(TvBorder)
            )

            // Center: Navigation Menu Icons
            TvNavItem.values().forEach { item ->
                TvSidebarIconButton(
                    item = item,
                    isSelected = item == selectedItem,
                    onClick = { onItemSelected(item) }
                )
            }

            Spacer(
                modifier = Modifier
                    .width(28.dp)
                    .height(1.dp)
                    .background(TvBorder)
            )

            // Bottom: Status Indicator Dot (Green = Listening)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(TvBgSubtle),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(SuccessGreen, CircleShape)
                )
            }
        }
    }
}

/**
 * Remote D-Pad focusable icon navigation button.
 * Automatically navigates to the screen on focus traversal without requiring an OK/Center click.
 */
@Composable
private fun TvSidebarIconButton(
    item: TvNavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // When navigated to with TV remote D-Pad, automatically activate the screen
    LaunchedEffect(isFocused) {
        if (isFocused && !isSelected) {
            onClick()
        }
    }

    val backgroundColor = when {
        isFocused -> TvBgElevated
        isSelected -> TvBgSurface
        else -> Color.Transparent
    }

    val borderColor = when {
        isFocused -> TvBorderFocused
        isSelected -> SkyBlue.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val contentColor = when {
        isFocused -> SkyBlue
        isSelected -> TvTextMain
        else -> TvTextSecondary
    }

    Box(
        modifier = Modifier
            .size(42.dp)
            .scale(if (isFocused) 1.1f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .border(
                width = if (isFocused || isSelected) 2.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
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
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = contentColor,
            modifier = Modifier.size(19.dp)
        )
    }
}
