package com.hearttoheart.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand Colors
val Coral = Color(0xFFFF6B6B)
val CoralDark = Color(0xFFE85555)
val CoralLight = Color(0xFFFF8A8A)
val SoftWhite = Color(0xFFFAF9F6)

// Category Colors
val FlutterColor = Color(0xFFFFB6C1)
val NudgeColor = Color(0xFFFFD93D)
val HeartbeatColor = Color(0xFFFF6B6B)
val LifelineColor = Color(0xFFFF3B3B)

private val LightColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = CoralLight,
    onPrimaryContainer = Color.Black,
    secondary = CoralLight,
    onSecondary = Color.Black,
    background = SoftWhite,
    onBackground = Color(0xFF2D2D2D),
    surface = Color.White,
    onSurface = Color(0xFF2D2D2D),
    error = LifelineColor,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color.Black,
    primaryContainer = CoralDark,
    onPrimaryContainer = Color.White,
    secondary = Coral,
    onSecondary = Color.Black,
    background = Color(0xFF1A1A1A),
    onBackground = Color.White,
    surface = Color(0xFF2D2D2D),
    onSurface = Color.White,
    error = LifelineColor,
    onError = Color.White
)

@Composable
fun HeartToHeartTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
