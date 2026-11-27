package com.famage.skydrop_tv_app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── SkyDrop Brand Accent Palette ──────────────────────────────────────────
val SkyBlue = Color(0xFF0EA5E9)           // Primary accent
val SkyBlueHover = Color(0xFF0284C7)      // Focused / Hover
val SkyBlueDark = Color(0xFF0369A1)       // Subtle accent
val TealAccent = Color(0xFF14B8A6)        // Status / Secondary accent
val TealAccentDark = Color(0xFF2DD4BF)    // High contrast teal
val SuccessGreen = Color(0xFF10B981)      // Online / Connected status
val DangerRed = Color(0xFFEF4444)         // Alerts / Error

/**
 * TV Colors container holding dynamic theme colors for both Dark and Light TV modes.
 */
data class TvColors(
    val bgApp: Color,
    val bgSidebar: Color,
    val bgSurface: Color,
    val bgElevated: Color,
    val bgSubtle: Color,
    val cardBg: Color,
    val cardHover: Color,
    val border: Color,
    val borderFocused: Color,
    val textMain: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val isDark: Boolean = true
)

val TvDarkPalette = TvColors(
    bgApp = Color(0xFF070B14),
    bgSidebar = Color(0xFF0B101D),
    bgSurface = Color(0xFF111827),
    bgElevated = Color(0xFF1E293B),
    bgSubtle = Color(0xFF162032),
    cardBg = Color(0xFF111827),
    cardHover = Color(0xFF1E293B),
    border = Color(0x1FFFFFFF),
    borderFocused = Color(0xFF38BDF8),
    textMain = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    isDark = true
)

val TvLightPalette = TvColors(
    bgApp = Color(0xFFF1F5F9),           // Soft clean light background
    bgSidebar = Color(0xFFE2E8F0),       // Slate light sidebar
    bgSurface = Color(0xFFFFFFFF),       // Crisp white cards
    bgElevated = Color(0xFFF8FAFC),      // Elevated surface
    bgSubtle = Color(0xFFE2E8F0),        // Subtle surface
    cardBg = Color(0xFFFFFFFF),          // White cards
    cardHover = Color(0xFFE0F2FE),       // Sky light highlight on focus
    border = Color(0x1F000000),          // 12% black border
    borderFocused = Color(0xFF0284C7),   // Vibrant blue focused border
    textMain = Color(0xFF0F172A),        // Dark slate text
    textSecondary = Color(0xFF475569),   // Medium slate text
    textMuted = Color(0xFF64748B),       // Dimmed slate text
    isDark = false
)

val LocalTvColors = compositionLocalOf { TvDarkPalette }

val TvBgApp: Color @Composable get() = LocalTvColors.current.bgApp
val TvBgSidebar: Color @Composable get() = LocalTvColors.current.bgSidebar
val TvBgSurface: Color @Composable get() = LocalTvColors.current.bgSurface
val TvBgElevated: Color @Composable get() = LocalTvColors.current.bgElevated
val TvBgSubtle: Color @Composable get() = LocalTvColors.current.bgSubtle
val TvCardBg: Color @Composable get() = LocalTvColors.current.cardBg
val TvCardHover: Color @Composable get() = LocalTvColors.current.cardHover
val TvBorder: Color @Composable get() = LocalTvColors.current.border
val TvBorderFocused: Color @Composable get() = LocalTvColors.current.borderFocused
val TvTextMain: Color @Composable get() = LocalTvColors.current.textMain
val TvTextSecondary: Color @Composable get() = LocalTvColors.current.textSecondary
val TvTextMuted: Color @Composable get() = LocalTvColors.current.textMuted
