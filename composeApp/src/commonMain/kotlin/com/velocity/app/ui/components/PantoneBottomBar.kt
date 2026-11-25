package com.velocity.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.VelocityTab
import com.velocity.app.platform.liquidGlassBackdropFilter
import com.velocity.app.ui.theme.LocalIsDarkTheme
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.concentricRadius

/**
 * Floating translucent Liquid Glass dock modeled directly after the 21st.dev `LiquidButton`:
 * - Live optical backdrop blur + displacement refraction (`#container-glass`) from `backdropLayer`
 * - Multi-stop opposing inner specular catchlights + inner frosted vignette glow
 * - Smooth spring-synced pill indicator tracking swipes & clicks in real time
 */
@Composable
fun PantoneBottomBar(
    selectedTab: VelocityTab,
    onTabSelected: (VelocityTab) -> Unit,
    modifier: Modifier = Modifier,
    pageProgress: Float = selectedTab.ordinal.toFloat(),
    backdropLayer: GraphicsLayer? = null,
) {
    val a11y = LocalVelocityA11y.current
    val isDark = LocalIsDarkTheme.current
    val semantic = LocalVelocitySemantic.current
    val outerRadius = 32.dp
    val pad = 5.dp
    val innerRadius = concentricRadius(outerRadius, pad)
    val tabs = VelocityTab.entries
    val pillShape = remember { RoundedCornerShape(outerRadius) }
    var dockPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .onGloballyPositioned { coords ->
                    dockPositionInRoot = coords.positionInRoot()
                }
                .shadow(
                    elevation = 16.dp,
                    shape = pillShape,
                    clip = false,
                    ambientColor = if (isDark) Color.Black.copy(alpha = 0.55f) else VelocityColors.ObsidianVoid.copy(alpha = 0.16f),
                    spotColor = if (isDark) Color.Black.copy(alpha = 0.70f) else VelocityColors.ImperialViolet.copy(alpha = 0.18f),
                )
                .clip(pillShape),
        ) {
            // 1. Live Optical #container-glass Backdrop Layer (blurred + wave-displaced real content behind pill)
            if (backdropLayer != null && !a11y.reduceTransparency) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .liquidGlassBackdropFilter(reduceTransparency = false)
                        .drawBehind {
                            translate(left = -dockPositionInRoot.x, top = -dockPositionInRoot.y) {
                                drawLayer(backdropLayer)
                            }
                        },
                )
            }

            // 2. Subtle frosted glass tint wash (low opacity so optical refraction remains visible)
            val tintColor = when {
                a11y.reduceTransparency -> semantic.card
                isDark -> VelocityColors.RoyalPlum.copy(alpha = 0.34f)
                else -> Color.White.copy(alpha = 0.42f)
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(tintColor),
            )

            // 3. Multi-layered inset specular shadows & inner glow from LiquidButton
            if (!a11y.reduceTransparency) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val cornerPx = outerRadius.toPx()
                    val cr = CornerRadius(cornerPx, cornerPx)

                    // Inner frosted vignette glow (matches CSS: inset 0 0 6px 6px rgba(255,255,255,0.12))
                    val innerGlowColor = if (isDark) Color.White else VelocityColors.ImperialViolet
                    drawRoundRect(
                        color = innerGlowColor.copy(alpha = if (isDark) 0.10f else 0.05f),
                        cornerRadius = cr,
                        style = Stroke(width = 6.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (isDark) 0.08f else 0.25f),
                        cornerRadius = cr,
                        style = Stroke(width = 2.dp.toPx()),
                    )

                    // Opposing asymmetric top-left & bottom-right specular rim catchlight
                    // (matches CSS: inset 1px 1px white 0.6 + inset -3px -3px white 0.85)
                    val highlightColor = if (isDark) VelocityColors.FrostLilac else Color.White
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            0.0f to highlightColor.copy(alpha = if (a11y.highContrast) 0.85f else 0.58f),
                            0.35f to highlightColor.copy(alpha = 0.10f),
                            0.65f to highlightColor.copy(alpha = 0.14f),
                            1.0f to highlightColor.copy(alpha = if (a11y.highContrast) 0.90f else 0.78f),
                        ),
                        cornerRadius = cr,
                        style = Stroke(width = if (a11y.highContrast) 1.6.dp.toPx() else 1.25.dp.toPx()),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(1.dp, semantic.cardBorder, pillShape),
                )
            }

            // 4. Synchronized Sliding Accent Pill + Tab Targets
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                val tabWidth = maxWidth / tabs.size
                val clampedProgress = pageProgress.coerceIn(0f, (tabs.size - 1).toFloat())

                // Crisp Imperial Violet pill indicator tracking swipe & tap offset smoothly
                Box(
                    modifier = Modifier
                        .offset(x = tabWidth * clampedProgress)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(innerRadius))
                        .background(VelocityColors.ImperialViolet.copy(alpha = 0.92f))
                        .border(
                            width = 1.dp,
                            color = VelocityColors.FrostLilac.copy(alpha = 0.32f),
                            shape = RoundedCornerShape(innerRadius),
                        ),
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) VelocityColors.FrostLilac else semantic.primaryInk.copy(alpha = 0.70f),
                            animationSpec = tween(160),
                        )
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected && !a11y.reduceMotion) 1.08f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(innerRadius))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    onTabSelected(tab)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                                ) {
                                    when (tab) {
                                        VelocityTab.BOND -> VelocityIcons.LightningBolt(
                                            color = contentColor,
                                            size = 18.dp,
                                            filled = isSelected,
                                        )
                                        VelocityTab.RELAYS -> VelocityIcons.GlobeIcon(
                                            color = contentColor,
                                            size = 18.dp,
                                        )
                                        VelocityTab.SETTINGS -> VelocityIcons.SettingsSliders(
                                            color = contentColor,
                                            size = 18.dp,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = contentColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
