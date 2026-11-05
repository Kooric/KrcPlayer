package com.kooric.krcplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun KrcPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KrcColorScheme,
        content = content
    )
}
