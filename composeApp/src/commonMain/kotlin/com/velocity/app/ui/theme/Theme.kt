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
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

data class VelocitySemanticColors(
    val canvas: Color,
    val card: Color,
    val cardBorder: Color,
    val mutedSurface: Color,
    val primaryInk: Color,
    val secondaryInk: Color,
    val accent: Color,
    val highlight: Color,
    val onAccent: Color,
    val successInk: Color,
    val successBg: Color,
    val warningInk: Color,
    val warningBg: Color,
    val errorInk: Color,
    val errorBg: Color,
    val wifiTint: Color,
    val wifiBg: Color,
    val cellTint: Color,
    val cellBg: Color,
)

val LocalVelocityA11y = staticCompositionLocalOf { VelocityA11ySettings() }
val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalVelocitySemantic = staticCompositionLocalOf {
    VelocitySemanticColors(
        canvas = VelocityColors.ObsidianVoid,
        card = VelocityColors.RoyalPlum,
        cardBorder = VelocityColors.ObsidianBorder,
        mutedSurface = VelocityColors.ObsidianSurfaceSubtle,
        primaryInk = VelocityColors.FrostLilac,
        secondaryInk = VelocityColors.ObsidianInkMuted,
        accent = VelocityColors.ImperialViolet,
        highlight = VelocityColors.LuminousLavender,
        onAccent = VelocityColors.FrostLilac,
        successInk = VelocityColors.SuccessDark,
        successBg = VelocityColors.SuccessContainerDark,
        warningInk = VelocityColors.WarningDark,
        warningBg = VelocityColors.WarningContainerDark,
        errorInk = VelocityColors.ErrorDark,
        errorBg = VelocityColors.ErrorContainerDark,
        wifiTint = VelocityColors.WifiAccentDark,
        wifiBg = VelocityColors.WifiSurfaceDark,
        cellTint = VelocityColors.CellAccentDark,
        cellBg = VelocityColors.CellSurfaceDark,
    )
}

private val LilacLightColorScheme = lightColorScheme(
    primary = VelocityColors.ImperialViolet,
    onPrimary = VelocityColors.FrostLilac,
    primaryContainer = VelocityColors.LuminousLavender,
    onPrimaryContainer = VelocityColors.ObsidianVoid,
    secondary = VelocityColors.VelvetAmethyst,
    onSecondary = VelocityColors.FrostLilac,
    tertiary = VelocityColors.LuminousLavender,
    onTertiary = VelocityColors.ObsidianVoid,
    background = VelocityColors.FrostLilac,
    onBackground = VelocityColors.ObsidianVoid,
    surface = VelocityColors.LilacElevated,
    onSurface = VelocityColors.ObsidianVoid,
    surfaceVariant = VelocityColors.LilacMuted,
    onSurfaceVariant = VelocityColors.LilacInkMuted,
    outline = VelocityColors.LilacBorder,
    error = VelocityColors.ErrorLight,
    onError = Color.White,
)

private val ObsidianDarkColorScheme = darkColorScheme(
    primary = VelocityColors.ImperialViolet,
    onPrimary = VelocityColors.FrostLilac,
    primaryContainer = VelocityColors.VelvetAmethyst,
    onPrimaryContainer = VelocityColors.FrostLilac,
    secondary = VelocityColors.LuminousLavender,
    onSecondary = VelocityColors.ObsidianVoid,
    tertiary = VelocityColors.FrostLilac,
    onTertiary = VelocityColors.ObsidianVoid,
    background = VelocityColors.ObsidianVoid,
    onBackground = VelocityColors.FrostLilac,
    surface = VelocityColors.RoyalPlum,
    onSurface = VelocityColors.FrostLilac,
    surfaceVariant = VelocityColors.ObsidianSurfaceSubtle,
    onSurfaceVariant = VelocityColors.ObsidianInkMuted,
    outline = VelocityColors.ObsidianBorder,
    error = VelocityColors.ErrorDark,
    onError = VelocityColors.ObsidianVoid,
)

@Composable
fun VelocityTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reduceTransparency: Boolean = false,
    reduceMotion: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (isDark) ObsidianDarkColorScheme else LilacLightColorScheme
    val semantic = if (isDark) {
        VelocitySemanticColors(
            canvas = VelocityColors.ObsidianVoid,
            card = VelocityColors.RoyalPlum,
            cardBorder = if (highContrast) VelocityColors.FrostLilac.copy(alpha = 0.55f) else VelocityColors.ObsidianBorder,
            mutedSurface = VelocityColors.ObsidianSurfaceSubtle,
            primaryInk = VelocityColors.FrostLilac,
            secondaryInk = VelocityColors.ObsidianInkMuted,
            accent = VelocityColors.ImperialViolet,
            highlight = VelocityColors.LuminousLavender,
            onAccent = VelocityColors.FrostLilac,
            successInk = VelocityColors.SuccessDark,
            successBg = VelocityColors.SuccessContainerDark,
            warningInk = VelocityColors.WarningDark,
            warningBg = VelocityColors.WarningContainerDark,
            errorInk = VelocityColors.ErrorDark,
            errorBg = VelocityColors.ErrorContainerDark,
            wifiTint = VelocityColors.WifiAccentDark,
            wifiBg = VelocityColors.WifiSurfaceDark,
            cellTint = VelocityColors.CellAccentDark,
            cellBg = VelocityColors.CellSurfaceDark,
        )
    } else {
        VelocitySemanticColors(
            canvas = VelocityColors.FrostLilac,
            card = VelocityColors.LilacElevated,
            cardBorder = if (highContrast) VelocityColors.ImperialViolet.copy(alpha = 0.55f) else VelocityColors.LilacBorder,
            mutedSurface = VelocityColors.LilacMuted,
            primaryInk = VelocityColors.ObsidianVoid,
            secondaryInk = VelocityColors.LilacInkMuted,
            accent = VelocityColors.ImperialViolet,
            highlight = VelocityColors.ImperialViolet,
            onAccent = VelocityColors.FrostLilac,
            successInk = VelocityColors.SuccessLight,
            successBg = VelocityColors.SuccessContainerLight,
            warningInk = VelocityColors.WarningLight,
            warningBg = VelocityColors.WarningContainerLight,
            errorInk = VelocityColors.ErrorLight,
            errorBg = VelocityColors.ErrorContainerLight,
            wifiTint = VelocityColors.WifiAccentLight,
            wifiBg = VelocityColors.WifiSurfaceLight,
            cellTint = VelocityColors.CellAccentLight,
            cellBg = VelocityColors.CellSurfaceLight,
        )
    }

    val a11y = VelocityA11ySettings(
        reduceTransparency = reduceTransparency,
        reduceMotion = reduceMotion,
        highContrast = highContrast,
    )
    val typography = rememberVelocityTypography()

    CompositionLocalProvider(
        LocalVelocityA11y provides a11y,
        LocalIsDarkTheme provides isDark,
        LocalVelocitySemantic provides semantic,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}
