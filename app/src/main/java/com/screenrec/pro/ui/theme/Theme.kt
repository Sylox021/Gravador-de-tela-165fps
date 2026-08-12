package com.screenrec.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { DARK, LIGHT, SYSTEM }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CD3FF),
    secondary = Color(0xFF9BE39B),
    tertiary = Color(0xFFFFB4A9)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00668B),
    secondary = Color(0xFF2E6B2E),
    tertiary = Color(0xFF8F4C40)
)

@Composable
fun ScreenRecorderTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
