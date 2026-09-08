package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = MotoAmberPrimary,
    onPrimary = MotoOnAmber,
    primaryContainer = MotoAmberContainer,
    onPrimaryContainer = MotoAmberPrimary,
    secondary = MotoCyanSecondary,
    onSecondary = MotoOnCyan,
    secondaryContainer = MotoCyanContainer,
    onSecondaryContainer = MotoCyanSecondary,
    background = MotoBackground,
    onBackground = MotoTextPrimary,
    surface = MotoSurface,
    onSurface = MotoTextPrimary,
    surfaceVariant = MotoSurfaceVariant,
    onSurfaceVariant = MotoTextSecondary,
    outline = MotoCardBorder,
    error = MotoRedError,
    onError = MotoBackground
)

@Composable
fun MotoNavTheme(
    content: @Composable () -> Unit
) {
    // MotoNav is primarily a dark cockpit motorcycle app
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

// Backwards compatibility alias for default template
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MotoNavTheme(content = content)
}

