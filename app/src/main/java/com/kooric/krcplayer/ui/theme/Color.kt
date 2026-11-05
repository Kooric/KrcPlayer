package com.kooric.krcplayer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ピュアブラック基調 ＆ エレクトリックブルーアクセント
val PureBlack = Color(0xFF07090E)
val SurfaceDark = Color(0xFF11151F)
val SurfaceElevated = Color(0xFF181E2C)
val CardBorder = Color(0xFF232C40)

val ElectricBlue = Color(0xFF2563EB)
val ElectricBlueBright = Color(0xFF3B82F6)
val ElectricBlueGlow = Color(0xFF60A5FA)
val DeepBlueSurface = Color(0xFF172554)

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val DangerRed = Color(0xFFEF4444)

val KrcColorScheme = darkColorScheme(
    primary = ElectricBlueBright,
    onPrimary = Color.White,
    primaryContainer = DeepBlueSurface,
    onPrimaryContainer = ElectricBlueGlow,
    secondary = ElectricBlueGlow,
    onSecondary = PureBlack,
    background = PureBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    error = DangerRed
)
