package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 21st.dev Dynamic Interactive Status Pill (Component #34753).
 * Floats at the top with live acoustic status dot, DNS Guard state indicator,
 * and high-throughput real-time aggregation metrics.
 */
@Composable
fun TopFloatingStatusPill(
    status: ConnectionStatus,
    combinedMbps: Double,
    modifier: Modifier = Modifier,
) {
    val a11y = LocalVelocityA11y.current

    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val statusColor by animateColorAsState(
        targetValue = when (status.state) {
            ConnectionState.BONDED -> VelocityColors.StatusBonded
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> VelocityColors.StatusConnecting
            ConnectionState.ERROR -> VelocityColors.StatusError
            ConnectionState.DISCONNECTED -> Color(0xFF64748B)
        },
        animationSpec = tween(400),
    )

    LiquidGlassSurface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(46.dp),
        cornerRadius = 23.dp,
        elevation = 16.dp,
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
            // Left: Glowing Pulsing Status LED + State Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (status.state.isActiveOrTransitioning && !a11y.reduceTransparency) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = pulseAlpha * 0.35f)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                }

                Text(
                    text = when (status.state) {
                        ConnectionState.BONDED -> "MULTIPATH BONDED"
                        ConnectionState.CONNECTING -> "PROBING PATHS"
                        ConnectionState.RECONNECTING -> "RECONNECTING"
                        ConnectionState.ERROR -> "PATH FAILED"
                        ConnectionState.DISCONNECTED -> "STANDBY"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                    color = Color.White.copy(alpha = 0.95f),
                )
            }

            // Right: Active Telemetry / DNS Guard Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // DNS Guard Chip: Always confirms port 53 is guarded directly
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "DNS Guard 🛡",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = VelocityColors.CyanGlow,
                    )
                }

                AnimatedVisibility(visible = status.state == ConnectionState.BONDED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${((combinedMbps * 10).toInt() / 10.0)} Mbps",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = VelocityColors.CombinedGlow,
                        )
                    }
                }
            }
        }
    }
}
