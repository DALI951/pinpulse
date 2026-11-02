package com.pinpulse.radar.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PulseGreen,
    onPrimary = Color.Black,
    secondary = RadarBlue,
    onSecondary = Color.White,
    background = RadarDarkBg,
    surface = RadarCardBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = DangerRed
)

@Composable
fun PinPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // PinPulse uses modern dark radar palette by default for maximum contrast with maps
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
