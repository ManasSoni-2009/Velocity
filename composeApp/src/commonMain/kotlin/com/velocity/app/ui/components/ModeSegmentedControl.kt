package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.BondingMode
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.concentricRadius

/**
 * Spring-animated segmented pill selector for Fastest / Low Lag / Backup modes,
 * accompanied by a calm 1-line description.
 */
@Composable
fun ModeSegmentedControl(
    activeMode: BondingMode,
    onModeSelected: (BondingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current
    val outerRadius = 26.dp
    val pad = 4.dp
    val innerRadius = concentricRadius(outerRadius, pad)
    val modes = BondingMode.entries
    val selectedIndex = modes.indexOf(activeMode).coerceAtLeast(0)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            cornerRadius = outerRadius,
            elevation = 4.dp,
            reduceTransparency = a11y.reduceTransparency,
            highContrast = a11y.highContrast,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                val itemWidth = maxWidth / modes.size
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex,
                    animationSpec = if (a11y.reduceMotion) {
                        tween(0)
                    } else {
                        spring(
                            dampingRatio = 0.84f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                    },
                )

                // Crisp solid Imperial Violet indicator pill (no muddy gradients)
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(innerRadius))
                        .background(VelocityColors.ImperialViolet)
                        .border(
                            1.dp,
                            VelocityColors.LuminousLavender.copy(alpha = 0.50f),
                            RoundedCornerShape(innerRadius),
                        ),
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    modes.forEach { mode ->
                        val isSelected = mode == activeMode
                        val labelColor by animateColorAsState(
                            targetValue = if (isSelected) VelocityColors.FrostLilac else semantic.primaryInk,
                            animationSpec = tween(200),
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
                                onModeSelected(mode)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            when (mode) {
                                BondingMode.SPEED -> VelocityIcons.LightningBolt(
                                    color = labelColor,
                                    size = 15.dp,
                                    filled = isSelected,
                                )
                                BondingMode.LOW_LATENCY -> VelocityIcons.TargetCrosshair(
                                    color = labelColor,
                                    size = 15.dp,
                                )
                                BondingMode.FAILOVER -> VelocityIcons.ShieldIcon(
                                    color = labelColor,
                                    size = 15.dp,
                                    filled = isSelected,
                                )
                            }
                                Text(
                                    text = mode.shortLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = labelColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = activeMode.subtitle,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
        ) { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.secondaryInk,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
