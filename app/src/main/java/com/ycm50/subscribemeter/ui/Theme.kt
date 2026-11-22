package com.ycm50.subscribemeter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EE7B7),
    onPrimary = Color(0xFF06281E),
    primaryContainer = Color(0xFF11483B),
    secondary = Color(0xFF93C5FD),
    surface = Color(0xFF11161D),
    surfaceVariant = Color(0xFF1B222C),
    background = Color(0xFF0E1116),
    error = Color(0xFFFCA5A5),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF1D4ED8),
    surface = Color(0xFFF7F8FA),
    background = Color(0xFFFFFFFF),
)

@Composable
fun SubscribeMeterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

/** 进度条配色:按已用比例给绿 / 黄 / 红三档。 */
fun meterColor(percent: Float): Color = when {
    percent >= 90f -> Color(0xFFF87171)
    percent >= 70f -> Color(0xFFFBBF24)
    else -> Color(0xFF34D399)
}
