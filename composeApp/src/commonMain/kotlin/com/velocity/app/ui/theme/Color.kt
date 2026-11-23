package com.velocity.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object VelocityColors {
    // Dark Canvas (Deep Oceanic / Obsidian)
    val DarkCanvas = Color(0xFF090D14)
    val DarkCanvasElevated = Color(0xFF0E1420)
    val DarkContentCard = Color(0xFF131B2A)
    val DarkContentCardBorder = Color(0xFF1E2B40)

    // Light Canvas (Crisp Daylight)
    val LightCanvas = Color(0xFFF3F5F9)
    val LightCanvasElevated = Color(0xFFFFFFFF)
    val LightContentCard = Color(0xFFFFFFFF)
    val LightContentCardBorder = Color(0xFFE2E8F0)

    // Liquid Glass Functional Layer Tints (Dark)
    val GlassDarkBackground = Color(0x33101827)
    val GlassDarkTint = Color(0x1A2563EB)
    val GlassDarkRimTop = Color(0x66FFFFFF)
    val GlassDarkRimBottom = Color(0x0DFFFFFF)

    // Liquid Glass Functional Layer Tints (Light)
    val GlassLightBackground = Color(0x66FFFFFF)
    val GlassLightTint = Color(0x140284C7)
    val GlassLightRimTop = Color(0xB3FFFFFF)
    val GlassLightRimBottom = Color(0x26000000)

    // Reduce Transparency Fallbacks
    val OpaqueFallbackDark = Color(0xFF161F30)
    val OpaqueFallbackLight = Color(0xFFEEF2F6)

    // Velocity Brand & Beam Accents
    val CyanGlow = Color(0xFF00E5FF)
    val ElectricBlue = Color(0xFF0070F3)
    val DeepBlue = Color(0xFF0051C7)
    val VioletAccent = Color(0xFF7928CA)
    val PurpleGlow = Color(0xFF9061F9)

    // Multi-Link Colors
    val WifiLink = Color(0xFF00D2FF)
    val WifiLinkEnd = Color(0xFF0070F3)
    val CellularLink = Color(0xFFFF5E62)
    val CellularLinkEnd = Color(0xFFFF9966)
    val CombinedGlow = Color(0xFF10B981)

    // Status Colors
    val StatusBonded = Color(0xFF10B981)
    val StatusConnecting = Color(0xFFF59E0B)
    val StatusError = Color(0xFFEF4444)
    val StatusIdle = Color(0xFF6B7280)

    // Specular Highlight Rim Brushes
    fun specularRim(isDark: Boolean, highContrast: Boolean): Brush {
        val topAlpha = when {
            highContrast && isDark -> 0.85f
            highContrast && !isDark -> 0.95f
            isDark -> 0.45f
            else -> 0.70f
        }
        val bottomAlpha = when {
            highContrast && isDark -> 0.20f
            highContrast && !isDark -> 0.30f
            isDark -> 0.06f
            else -> 0.12f
        }
        val rimColor = if (isDark) Color.White else Color.White
        val bottomColor = if (isDark) Color.White else Color(0xFF94A3B8)
        return Brush.verticalGradient(
            0.0f to rimColor.copy(alpha = topAlpha),
            0.4f to rimColor.copy(alpha = topAlpha * 0.4f),
            1.0f to bottomColor.copy(alpha = bottomAlpha),
        )
    }

    // Orb Gradients
    val OrbIdleGradient = Brush.radialGradient(
        colors = listOf(Color(0xFF334155), Color(0xFF0F172A)),
    )
    val OrbBondedGradient = Brush.radialGradient(
        colors = listOf(Color(0xFF00E5FF), Color(0xFF0070F3), Color(0xFF7928CA)),
    )
    val OrbConnectingGradient = Brush.radialGradient(
        colors = listOf(Color(0xFFFCD34D), Color(0xFFF59E0B), Color(0xFFB45309)),
    )
    val OrbErrorGradient = Brush.radialGradient(
        colors = listOf(Color(0xFFF87171), Color(0xFFDC2626), Color(0xFF7F1D1D)),
    )

    // Dual Path Beam Gradients
    val WifiBeamBrush = Brush.horizontalGradient(
        colors = listOf(WifiLink, WifiLinkEnd),
    )
    val CellularBeamBrush = Brush.horizontalGradient(
        colors = listOf(CellularLink, CellularLinkEnd),
    )
    val CombinedBeamBrush = Brush.horizontalGradient(
        colors = listOf(WifiLink, CombinedGlow, CellularLink),
    )
}
