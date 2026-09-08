package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// MotoNav "Immersive UI" Dark Theme Palette
val MotoBackground = Color(0xFF0F1113)
val MotoSurface = Color(0xFF16191C)
val MotoSurfaceVariant = Color(0xFF1F2327)
val MotoSurfaceContainerHigh = Color(0xFF282D33)
val MotoCardBorder = Color(0xFF2C3136)

// Gradient cards per Immersive UI spec (from #1C1F22 to #121416)
val MotoGradientStart = Color(0xFF1C1F22)
val MotoGradientEnd = Color(0xFF121416)
val MotoCardGradient = Brush.verticalGradient(
    colors = listOf(MotoGradientStart, MotoGradientEnd)
)
val MotoCardGradientDiagonal = Brush.linearGradient(
    colors = listOf(MotoGradientStart, MotoGradientEnd)
)

// Immersive UI Primary Accent: #FF6B00 with vibrant glow
val MotoAmberPrimary = Color(0xFFFF6B00)       // Immersive UI Accent #FF6B00
val MotoAmberDark = Color(0xFFE65100)
val MotoAmberLight = Color(0xFFFF8533)
val MotoAmberContainer = Color(0xFF351700)
val MotoOnAmber = Color(0xFF0F1113)
val MotoAccentGlow = Color(0x4DFF6B00)         // shadow-[0_0_20px_rgba(255,107,0,0.3)]

val MotoAccentGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFFFF6B00), Color(0xFFFF8533))
)

// HUD / GPS Secondary Accent
val MotoCyanSecondary = Color(0xFF00E5FF)      // HUD Vector / GPS Cyan
val MotoCyanContainer = Color(0xFF00333D)
val MotoOnCyan = Color(0xFF0F1113)

// Status Colors
val MotoLimeReady = Color(0xFF00E676)          // "Route Ready" / Active Connected Green
val MotoRedError = Color(0xFFFF5252)           // BLE Disconnect / Error
val MotoBlueConnecting = Color(0xFF448AFF)

// High-Contrast Text per Immersive UI spec: Text #E2E2E6
val MotoTextPrimary = Color(0xFFE2E2E6)
val MotoTextSecondary = Color(0xFF9A9EA5)
val MotoTextMuted = Color(0xFF62666D)
val MotoDivider = Color(0xFF22262B)

