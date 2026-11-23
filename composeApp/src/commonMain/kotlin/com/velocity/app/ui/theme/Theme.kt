package com.velocity.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.velocity.app.domain.ThemeMode

data class VelocityA11ySettings(
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
)

val LocalVelocityA11y = staticCompositionLocalOf { VelocityA11ySettings() }
val LocalIsDarkTheme = staticCompositionLocalOf { true }

private val DarkColorScheme = darkColorScheme(
    primary = VelocityColors.CyanGlow,
    onPrimary = Color.Black,
    primaryContainer = VelocityColors.ElectricBlue,
    onPrimaryContainer = Color.White,
    secondary = VelocityColors.VioletAccent,
    onSecondary = Color.White,
    background = VelocityColors.DarkCanvas,
    onBackground = Color.White,
    surface = VelocityColors.DarkContentCard,
    onSurface = Color.White,
    surfaceVariant = VelocityColors.DarkCanvasElevated,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = VelocityColors.DarkContentCardBorder,
)

private val LightColorScheme = lightColorScheme(
    primary = VelocityColors.ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = VelocityColors.DeepBlue,
    secondary = VelocityColors.VioletAccent,
    onSecondary = Color.White,
    background = VelocityColors.LightCanvas,
    onBackground = Color(0xFF0F172A),
    surface = VelocityColors.LightContentCard,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = VelocityColors.LightCanvasElevated,
    onSurfaceVariant = Color(0xFF64748B),
    outline = VelocityColors.LightContentCardBorder,
)

@Composable
fun VelocityTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    reduceTransparency: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val a11y = VelocityA11ySettings(
        reduceTransparency = reduceTransparency,
        highContrast = highContrast,
    )

    CompositionLocalProvider(
        LocalVelocityA11y provides a11y,
        LocalIsDarkTheme provides isDark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = VelocityTypography,
            content = content,
        )
    }
}
