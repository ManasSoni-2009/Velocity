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
import com.velocity.app.domain.BondingMode
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.concentricRadius

@Composable
fun ModeCapsuleSwitcher(
    activeMode: BondingMode,
    onModeSelected: (BondingMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalVelocityA11y.current
    val outerRadius = 26.dp
    val pillPadding = 4.dp
    val innerRadius = concentricRadius(outerRadius, pillPadding)

    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        cornerRadius = outerRadius,
        elevation = 12.dp,
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
            BondingMode.entries.forEach { mode ->
                val isSelected = mode == activeMode
                val activeBgColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (a11y.highContrast) Color(0xFF1E293B) else VelocityColors.ElectricBlue.copy(alpha = 0.25f)
                    } else {
                        Color.Transparent
                    },
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                )
                val glyph = when (mode) {
                    BondingMode.SPEED -> "🚀"
                    BondingMode.LOW_LATENCY -> "🎯"
                    BondingMode.FAILOVER -> "🛡"
                }

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
                            onModeSelected(mode)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = glyph,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = mode.shortLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}
