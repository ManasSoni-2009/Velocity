package com.velocity.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.velocity.app.ui.theme.VelocityColors
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * SkSL shader for authentic Apple Liquid Glass refraction and chromatic specular highlight.
 */
private const val LIQUID_GLASS_SKSL = """
    uniform shader content;
    uniform float2 resolution;
    uniform float cornerRadius;

    half4 main(float2 coord) {
        float2 uv = coord / resolution;
        // Subtle edge refraction displacement
        float2 center = float2(0.5, 0.5);
        float2 dist = uv - center;
        float r = length(dist);
        float2 refractOffset = dist * (r * r * 0.04);
        half4 color = content.eval(coord + refractOffset);

        // Luminous edge glow
        float edgeDist = min(min(coord.x, resolution.x - coord.x), min(coord.y, resolution.y - coord.y));
        if (edgeDist < 2.0) {
            float edgeFactor = 1.0 - (edgeDist / 2.0);
            color = mix(color, half4(1.0, 1.0, 1.0, 0.6), edgeFactor * 0.4);
        }
        return color;
    }
"""

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
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    if (reduceTransparency) {
        // High-contrast accessibility opaque fallback
        val opaqueBg = if (highContrast) Color(0xFF0F172A) else VelocityColors.OpaqueFallbackDark
        val borderColor = if (highContrast) Color.White.copy(alpha = 0.6f) else Color(0xFF334155)

        Box(
            modifier = modifier
                .shadow(elevation, shape)
                .clip(shape)
                .background(opaqueBg)
                .border(1.dp, borderColor, shape),
            content = content,
        )
        return
    }

    // Specular Rim Gradient
    val rimBrush = remember(highContrast) {
        VelocityColors.specularRim(isDark = true, highContrast = highContrast)
    }

    // Compile SkSL RuntimeEffect or fallback to Gaussian blur
    val skiaFilter = remember(cornerRadius) {
        try {
            val blur = ImageFilter.makeBlur(22f, 22f, FilterTileMode.CLAMP)
            val effect = RuntimeEffect.makeForShader(LIQUID_GLASS_SKSL)
            val builder = RuntimeShaderBuilder(effect)
            // Composite SkSL refraction shader with backdrop blur
            ImageFilter.makeRuntimeShader(builder, "content", blur)
        } catch (_: Throwable) {
            try {
                ImageFilter.makeBlur(20f, 20f, FilterTileMode.CLAMP)
            } catch (_: Throwable) {
                null
            }
        }
    }

    val composeRenderEffect = remember(skiaFilter) {
        skiaFilter?.asComposeRenderEffect()
    }

    val glassBackground = remember(tint) {
        if (tint == Color.Unspecified) {
            VelocityColors.GlassDarkBackground
        } else {
            tint.copy(alpha = 0.22f)
        }
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.5f),
                spotColor = Color.Black.copy(alpha = 0.7f),
            )
            .clip(shape)
            .border(
                width = if (highContrast) 1.5.dp else 1.dp,
                brush = rimBrush,
                shape = shape,
            ),
    ) {
        // Backdrop Blur & Refraction layer (does not blur child composables)
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    renderEffect = composeRenderEffect
                }
                .background(glassBackground),
        )

        // Pin-sharp child content layer
        content()
    }
}
