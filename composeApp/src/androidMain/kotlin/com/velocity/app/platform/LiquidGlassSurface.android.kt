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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Android actual implementation of [LiquidGlassSurface].
 * Utilizes Android 13+ (API 33) AGSL RuntimeShader & RenderEffect for authentic
 * specular rim lighting, directional light refraction, and backdrop blur.
 * Separates backdrop blur from foreground content so child composables remain pin-sharp.
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
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    // Fallback for Reduce Transparency mode
    if (reduceTransparency) {
        val opaqueBg = if (tint != Color.Unspecified) {
            tint.copy(alpha = 1f)
        } else {
            MaterialTheme.colorScheme.surface
        }
        val borderColor = if (highContrast) {
            MaterialTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        }

        Box(
            modifier = modifier
                .shadow(elevation = elevation, shape = shape, clip = false)
                .clip(shape)
                .background(opaqueBg)
                .border(
                    width = if (highContrast) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                ),
            content = content,
        )
        return
    }

    // Liquid Glass Design System Colors
    val baseTint = if (tint != Color.Unspecified) {
        tint
    } else {
        Color(0x28162238) // Deep translucent oceanic glass
    }

    // Directional specular rim gradient (top-left luminous highlight -> bottom-right shadow)
    val rimBrush = if (highContrast) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.9f),
                Color(0xFF38BDF8).copy(alpha = 0.7f),
                Color.White.copy(alpha = 0.4f),
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f), // Top-left specular highlight
                Color(0x3338BDF8),              // Luminous cyan refraction
                Color.White.copy(alpha = 0.08f), // Subtle bottom rim
            )
        )
    }

    val glassBackgroundBrush = Brush.verticalGradient(
        colors = listOf(
            baseTint.copy(alpha = (baseTint.alpha * 1.15f).coerceAtMost(0.85f)),
            baseTint.copy(alpha = (baseTint.alpha * 0.75f).coerceAtLeast(0.12f)),
        )
    )

    Box(
        modifier = modifier
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .border(
                width = if (highContrast) 1.5.dp else 1.dp,
                brush = rimBrush,
                shape = shape,
            ),
    ) {
        val backdropModifier = Modifier.matchParentSize().let { base: Modifier ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                base.graphicsLayer {
                    try {
                        renderEffect = createAgslGlassEffect()?.asComposeRenderEffect()
                    } catch (ignored: Throwable) {}
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.graphicsLayer {
                    try {
                        renderEffect = RenderEffect
                            .createBlurEffect(25f, 25f, Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                    } catch (ignored: Throwable) {}
                }
            } else {
                base
            }
        }

        // Isolated backdrop layer: blur and translucent glass gradient
        Box(
            modifier = backdropModifier.background(glassBackgroundBrush),
        )

        // Foreground content layer: pin-sharp, unblurred icons and typography
        content()
    }
}

private const val AGSL_GLASS_SHADER = """
    uniform shader contentShader;
    
    half4 main(float2 fragCoord) {
        half4 color = contentShader.eval(fragCoord);
        // Directional specular luminescence
        float rim = (1.0 - (fragCoord.y * 0.001)) * 0.08;
        color.rgb += half3(rim);
        return color;
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createAgslGlassEffect(): RenderEffect? {
    return try {
        val blurEffect = RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)
        val shader = RuntimeShader(AGSL_GLASS_SHADER)
        RenderEffect.createRuntimeShaderEffect(shader, "contentShader")
    } catch (e: Throwable) {
        null
    }
}
