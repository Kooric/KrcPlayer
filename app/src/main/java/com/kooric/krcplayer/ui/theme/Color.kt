package com.kooric.krcplayer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val DarkGray = Color(0xFF1E1E1E)
val LightGray = Color(0xFF2A2A2A)
val AccentBlue = Color(0xFF3B82F6)
val AccentBlueLight = Color(0xFF60A5FA)
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

val KrcColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentBlueLight,
    background = Color(0xFF121212),
    surface = DarkGray,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)
