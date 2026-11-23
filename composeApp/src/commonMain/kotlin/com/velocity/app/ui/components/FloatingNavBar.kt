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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.VelocityTab
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.concentricRadius

@Composable
fun FloatingNavBar(
    selectedTab: VelocityTab,
    onTabSelected: (VelocityTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalVelocityA11y.current
    val outerRadius = 32.dp
    val pillPadding = 4.dp
    val innerRadius = concentricRadius(outerRadius, pillPadding)

    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .height(64.dp),
        cornerRadius = outerRadius,
        elevation = 16.dp,
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
                    targetValue = if (isSelected) 1.0f else 0.94f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                )
                val activeBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (a11y.highContrast) Color(0xFF1E293B) else VelocityColors.CyanGlow.copy(alpha = 0.18f)
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
                    ) {
                        Text(
                            text = getTabGlyph(tab),
                            fontSize = 18.sp,
                            color = if (isSelected) VelocityColors.CyanGlow else Color.White.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tab.title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

private fun getTabGlyph(tab: VelocityTab): String = when (tab) {
    VelocityTab.BOND -> "⚡"
    VelocityTab.TELEMETRY -> "📊"
    VelocityTab.SERVERS -> "🌐"
    VelocityTab.SETTINGS -> "⚙"
}
