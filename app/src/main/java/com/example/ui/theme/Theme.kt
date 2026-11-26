package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FieldDarkColorScheme = darkColorScheme(
    primary = SignalAmber,
    onPrimary = Color(0xFF1A1100),
    primaryContainer = SignalAmberContainer,
    onPrimaryContainer = SignalAmber,
    secondary = ElectricCyan,
    onSecondary = Color(0xFF001E2C),
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = ElectricCyan,
    tertiary = StatusEmerald,
    onTertiary = Color(0xFF00210E),
    background = SlateBgDark,
    onBackground = SlateTextPrimaryDark,
    surface = SlateSurfaceDark,
    onSurface = SlateTextPrimaryDark,
    surfaceVariant = SlateSurfaceElevatedDark,
    onSurfaceVariant = SlateTextSecondaryDark,
    outline = SlateOutlineDark,
    error = StatusCrimson,
    onError = Color.White,
    errorContainer = StatusCrimsonBg,
    onErrorContainer = StatusCrimson
)

private val FieldLightColorScheme = lightColorScheme(
    primary = SignalAmberLight,
    onPrimary = Color.White,
    primaryContainer = SignalAmberLightContainer,
    onPrimaryContainer = SignalAmberDark,
    secondary = ElectricCyanLight,
    onSecondary = Color.White,
    secondaryContainer = ElectricCyanLightContainer,
    onSecondaryContainer = ElectricCyanLight,
    tertiary = StatusEmerald,
    onTertiary = Color.White,
    background = AlabasterBgLight,
    onBackground = AlabasterTextPrimaryLight,
    surface = AlabasterSurfaceLight,
    onSurface = AlabasterTextPrimaryLight,
    surfaceVariant = AlabasterSurfaceElevatedLight,
    onSurfaceVariant = AlabasterTextSecondaryLight,
    outline = AlabasterOutlineLight,
    error = StatusCrimson,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

@Composable
fun FieldCaptureTheme(
    darkTheme: Boolean = true, // Field-ready dark mode default; user can toggle daylight mode
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FieldDarkColorScheme else FieldLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FieldCaptureTypography,
        content = content
    )
}
