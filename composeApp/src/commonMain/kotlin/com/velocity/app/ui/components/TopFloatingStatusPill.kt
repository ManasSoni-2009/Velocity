package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.ConnectionStatus
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun TopFloatingStatusPill(
    status: ConnectionStatus,
    combinedMbps: Double,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalVelocityA11y.current
    val statusColor by animateColorAsState(
        targetValue = when (status.state) {
            ConnectionState.BONDED -> VelocityColors.StatusBonded
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> VelocityColors.StatusConnecting
            ConnectionState.ERROR -> VelocityColors.StatusError
            ConnectionState.DISCONNECTED -> VelocityColors.StatusIdle
        },
        animationSpec = tween(400),
    )

    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(44.dp),
        cornerRadius = 22.dp,
        elevation = 14.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Text(
                    text = status.state.label.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }

            AnimatedVisibility(visible = status.state == ConnectionState.BONDED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${((combinedMbps * 10).toInt() / 10.0)} Mbps",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = VelocityColors.CyanGlow,
                    )
                    Text(
                        text = "•",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                    Text(
                        text = status.activeMode.shortLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            if (status.state != ConnectionState.BONDED) {
                Text(
                    text = status.message,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
    }
}
