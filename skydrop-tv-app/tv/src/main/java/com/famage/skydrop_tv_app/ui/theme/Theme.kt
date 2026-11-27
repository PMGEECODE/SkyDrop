package com.famage.skydrop_tv_app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
private val SkyDropTvDarkColorScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFFF8FAFC),
    primaryContainer = SkyBlueDark,
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = TealAccent,
    onSecondary = Color(0xFFF8FAFC),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF162032),
    onSurfaceVariant = Color(0xFF94A3B8),
    border = Color(0x1FFFFFFF)
)

@OptIn(ExperimentalTvMaterial3Api::class)
private val SkyDropTvLightColorScheme = lightColorScheme(
    primary = SkyBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = TealAccent,
    onSecondary = Color.White,
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    border = Color(0x1F000000)
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SkydroptvappTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemDark
    }

    val palette = if (isDark) TvDarkPalette else TvLightPalette
    val materialScheme = if (isDark) SkyDropTvDarkColorScheme else SkyDropTvLightColorScheme

    CompositionLocalProvider(
        LocalTvColors provides palette
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = Typography,
            content = content
        )
    }
}
