package com.antoniopaess.netlens.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()
private val NetLensTypography = Typography()

/** Applies the shared Material 3 foundation used by every feature screen. */
@Composable
fun NetLensTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = NetLensTypography,
        content = content,
    )
}
