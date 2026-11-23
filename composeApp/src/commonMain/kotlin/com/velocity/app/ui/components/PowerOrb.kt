package com.velocity.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.ConnectionStatus
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun PowerOrb(
    status: ConnectionStatus,
    combinedMbps: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 190.dp,
) {
    val a11y = LocalVelocityA11y.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val activeScale = when {
        isPressed -> 0.92f
        status.state == ConnectionState.CONNECTING -> pulseScale
        status.state == ConnectionState.BONDED -> 1.0f
        else -> 0.98f
    }

    val animatedScale by animateFloatAsState(
        targetValue = activeScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
    )

    val activeBrush = when (status.state) {
        ConnectionState.BONDED -> VelocityColors.OrbBondedGradient
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> VelocityColors.OrbConnectingGradient
        ConnectionState.ERROR -> VelocityColors.OrbErrorGradient
        ConnectionState.DISCONNECTED -> VelocityColors.OrbIdleGradient
    }

    val glowColor = when (status.state) {
        ConnectionState.BONDED -> VelocityColors.CyanGlow
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> VelocityColors.StatusConnecting
        ConnectionState.ERROR -> VelocityColors.StatusError
        ConnectionState.DISCONNECTED -> Color.Transparent
    }

    Box(
        modifier = modifier
            .size(orbSize + 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer ethereal glow aura (disabled in reduceTransparency if requested)
        if (!a11y.reduceTransparency && status.state.isActiveOrTransitioning) {
            Box(
                modifier = Modifier
                    .size(orbSize + 28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = 0.35f),
                                glowColor.copy(alpha = 0.08f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        // Morphing Liquid Glass Orb Surface
        LiquidGlassSurface(
            modifier = Modifier
                .size(orbSize)
                .scale(animatedScale)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            cornerRadius = orbSize / 2,
            tint = glowColor.copy(alpha = 0.15f),
            elevation = 20.dp,
            reduceTransparency = a11y.reduceTransparency,
            highContrast = a11y.highContrast,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(activeBrush),
                contentAlignment = Alignment.Center,
            ) {
                // Inner Glass Specular Layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.White.copy(alpha = 0.30f),
                                0.5f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.25f),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = when (status.state) {
                                ConnectionState.BONDED -> "⚡"
                                ConnectionState.CONNECTING -> "🔄"
                                ConnectionState.RECONNECTING -> "📶"
                                ConnectionState.ERROR -> "⚠"
                                ConnectionState.DISCONNECTED -> "⏻"
                            },
                            fontSize = 38.sp,
                            color = Color.White,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = when (status.state) {
                                ConnectionState.BONDED -> "BONDED"
                                ConnectionState.CONNECTING -> "BONDING"
                                ConnectionState.RECONNECTING -> "RETRYING"
                                ConnectionState.ERROR -> "RECONNECT"
                                ConnectionState.DISCONNECTED -> "BOND NOW"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp,
                            color = Color.White,
                        )

                        if (status.state == ConnectionState.BONDED) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${((combinedMbps * 10).toInt() / 10.0)} Mbps",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
