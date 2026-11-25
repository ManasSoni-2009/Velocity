package com.velocity.app.platform

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.velocity.app.ui.theme.LocalIsDarkTheme
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors

/**
 * Calm matte card surface (zero heavy RenderEffect layers on scrolling cards)
 * for crisp 60/120fps scrolling and minimalist palette contrast.
 */
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
    if (reduceTransparency) return this
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val effect = createContainerGlassRenderEffect()
        if (effect != null) {
            graphicsLayer { renderEffect = effect.asComposeRenderEffect() }
        } else {
            blur(20.dp)
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        blur(20.dp)
    } else {
        this
    }
}

// Ports the SVG <filter id="container-glass"> (feTurbulence + feDisplacementMap + feGaussianBlur)
// into an Android 13+ AGSL RuntimeShader applied exclusively to the floating navbar backdrop.
private const val CONTAINER_GLASS_AGSL = """
    uniform shader contentShader;

    float hash21(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return fract(p.x * p.y);
    }

    float smoothNoise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        float2 u = f * f * (3.0 - 2.0 * f);
        float a = hash21(i);
        float b = hash21(i + float2(1.0, 0.0));
        float c = hash21(i + float2(0.0, 1.0));
        float d = hash21(i + float2(1.0, 1.0));
        return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
    }

    half4 main(float2 fragCoord) {
        float2 scaled = fragCoord * 0.035;
        float nx = smoothNoise(scaled) - 0.5;
        float ny = smoothNoise(scaled + float2(17.3, 9.7)) - 0.5;
        float2 displaced = fragCoord + float2(nx, ny) * 14.0;
        return contentShader.eval(displaced);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createContainerGlassRenderEffect(): RenderEffect? {
    return try {
        val preBlur = RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP)
        val shader = RuntimeShader(CONTAINER_GLASS_AGSL)
        val displace = RenderEffect.createRuntimeShaderEffect(shader, "contentShader")
        val finalBlur = RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP)
        RenderEffect.createChainEffect(finalBlur, RenderEffect.createChainEffect(displace, preBlur))
    } catch (_: Throwable) {
        null
    }
}
