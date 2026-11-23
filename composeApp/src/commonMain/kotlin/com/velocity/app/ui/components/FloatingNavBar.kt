package com.velocity.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.VelocityTab
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.concentricRadius

/**
 * 21st.dev inspired Floating Glass Navigation Bar.
 * Integrates concentric radius math, specular reflections, and pure vector icons.
 */
@Composable
fun FloatingNavBar(
    selectedTab: VelocityTab,
    onTabSelected: (VelocityTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalVelocityA11y.current
    val outerRadius = 32.dp
    val pillPadding = 5.dp
    val innerRadius = concentricRadius(outerRadius, pillPadding)

    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .height(66.dp),
        cornerRadius = outerRadius,
        elevation = 18.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(pillPadding),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VelocityTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.0f else 0.92f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) VelocityColors.CyanGlow else Color.White.copy(alpha = 0.45f),
                )
                val activeBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (a11y.highContrast) Color(0xFF1E293B) else VelocityColors.CyanGlow.copy(alpha = 0.16f)
                    } else {
                        Color.Transparent
                    },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(innerRadius))
                        .background(activeBgColor)
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
                        modifier = Modifier.scale(scale),
                    ) {
                        when (tab) {
                            VelocityTab.BOND -> VelocityIcons.LightningBolt(
                                color = iconColor,
                                size = 18.dp,
                                filled = isSelected,
                            )
                            VelocityTab.TELEMETRY -> VelocityIcons.AnalyticsChart(
                                color = iconColor,
                                size = 18.dp,
                            )
                            VelocityTab.SERVERS -> VelocityIcons.GlobeIcon(
                                color = iconColor,
                                size = 18.dp,
                            )
                            VelocityTab.SETTINGS -> VelocityIcons.SettingsSliders(
                                color = iconColor,
                                size = 18.dp,
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = tab.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.4.sp,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}
