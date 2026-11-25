package com.velocity.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Minimalist Violet-Obsidian & Frost Lilac color system anchored to the 6 core swatches:
 * - Obsidian Void:     #140E20 (canvas)
 * - Royal Plum:        #241738 (elevated cards & calm surfaces)
 * - Velvet Amethyst:   #3D2556 (hairline borders & quiet control wells)
 * - Imperial Violet:   #6C3C91 (reserved primary accent & active hero)
 * - Luminous Lavender: #A473C3 (subtle highlight accents & active rings)
 * - Frost Lilac:       #F5EDFA (crisp highlight ink & light canvas)
 */
object VelocityColors {
    val ObsidianVoid = Color(0xFF140E20)
    val RoyalPlum = Color(0xFF241738)
    val VelvetAmethyst = Color(0xFF3D2556)
    val ImperialViolet = Color(0xFF6C3C91)
    val LuminousLavender = Color(0xFFA473C3)
    val FrostLilac = Color(0xFFF5EDFA)

    // Restrained Neutrals & Borders (High WCAG contrast without visual purple noise)
    val LilacElevated = Color(0xFFFFFFFF)
    val LilacMuted = Color(0xFFEDE2F6)
    val LilacBorder = Color(0x243D2556) // 14% subtle architectural hairline
    val LilacInkMuted = Color(0x9E140E20) // 62% dark ink

    val ObsidianSurfaceSubtle = Color(0xFF1C132D)
    val ObsidianBorder = Color(0x703D2556) // calm hairline stroke
    val ObsidianInkMuted = Color(0xA3F5EDFA) // 64% cool mist white

    // Semantic Status Dots & Icons ONLY (never flood whole cards)
    val SuccessLight = Color(0xFF0E7C5B)
    val SuccessContainerLight = Color(0xFFFFFFFF)
    val WarningLight = Color(0xFFC2355D)
    val WarningContainerLight = Color(0xFFFFFFFF)
    val ErrorLight = Color(0xFFB3261E)
    val ErrorContainerLight = Color(0xFFFDF2F2)

    val SuccessDark = Color(0xFF34D399)
    val SuccessContainerDark = RoyalPlum
    val WarningDark = Color(0xFFFB7185)
    val WarningContainerDark = RoyalPlum
    val ErrorDark = Color(0xFFFCA5A5)
    val ErrorContainerDark = RoyalPlum

    // Link Icon Tints (Keep surfaces calm, tint icons & dots cleanly)
    val WifiAccentLight = ImperialViolet
    val WifiSurfaceLight = LilacElevated
    val CellAccentLight = LuminousLavender
    val CellSurfaceLight = LilacElevated

    val WifiAccentDark = LuminousLavender
    val WifiSurfaceDark = RoyalPlum
    val CellAccentDark = FrostLilac
    val CellSurfaceDark = RoyalPlum

    fun liquidGlassRimBrush(isDark: Boolean, highContrast: Boolean): Brush {
        val topLeft = if (isDark) FrostLilac.copy(alpha = if (highContrast) 0.72f else 0.42f) else Color.White.copy(alpha = 0.92f)
        val mid = LuminousLavender.copy(alpha = if (isDark) 0.16f else 0.28f)
        val bottomRight = if (isDark) FrostLilac.copy(alpha = if (highContrast) 0.85f else 0.62f) else ImperialViolet.copy(alpha = 0.25f)
        return Brush.linearGradient(
            0.0f to topLeft,
            0.48f to mid,
            1.0f to bottomRight,
        )
    }
}
