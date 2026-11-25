package com.velocity.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.velocity.app.ui.theme.LocalIsDarkTheme
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors

@Composable
actual fun LiquidGlassSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    tint: Color,
    elevation: Dp,
    reduceTransparency: Boolean,
    highContrast: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val semantic = LocalVelocitySemantic.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    val surfaceColor = when {
        tint != Color.Unspecified -> tint
        reduceTransparency -> semantic.card
        isDark -> VelocityColors.RoyalPlum
        else -> VelocityColors.LilacElevated
    }

    val borderColor = if (highContrast && isDark) {
        VelocityColors.FrostLilac.copy(alpha = 0.55f)
    } else {
        semantic.cardBorder
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(surfaceColor)
            .border(
                width = if (highContrast) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            ),
        content = content,
    )
}

actual fun Modifier.liquidGlassBackdropFilter(
    reduceTransparency: Boolean,
): Modifier {
    return if (reduceTransparency) this else blur(18.dp)
}
