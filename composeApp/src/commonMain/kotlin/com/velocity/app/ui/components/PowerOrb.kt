package com.velocity.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.ConnectionStatus
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.VelocityColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 21st.dev inspired Liquid Glass Core Orb (Component #34753 / #2759)
 * Features dynamic orbital particles, multi-layer optical refraction,
 * breathing acoustic waves, and state-adaptive vector glyphs.
 */
@Composable
fun PowerOrb(
    status: ConnectionStatus,
    combinedMbps: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 196.dp,
) {
    val a11y = LocalVelocityA11y.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition()

    // Breathing pulse for connecting / active states
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    // Orbital particles rotation angle (0 to 360 degrees)
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (status.state) {
                    ConnectionState.BONDED -> 3200
                    ConnectionState.CONNECTING -> 1800
                    else -> 12000
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
    )

    // Acoustic wave expanding radius (0 to 1)
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
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
        ConnectionState.DISCONNECTED -> Color(0xFF38BDF8)
    }

    Box(
        modifier = modifier.size(orbSize + 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer Ethereal Acoustic Waves (when bonded or connecting)
        if (!a11y.reduceTransparency && status.state.isActiveOrTransitioning) {
            Canvas(modifier = Modifier.size(orbSize + 44.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxRadius = size.width / 2f
                val baseRadius = (orbSize.toPx() / 2f)

                val r = baseRadius + (maxRadius - baseRadius) * waveProgress
                val alpha = (1f - waveProgress) * 0.45f
                drawCircle(
                    color = glowColor.copy(alpha = alpha),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Ambient Aura Glow
        if (!a11y.reduceTransparency) {
            val auraAlpha = if (status.state == ConnectionState.BONDED) 0.35f else 0.15f
            Box(
                modifier = Modifier
                    .size(orbSize + 28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = auraAlpha),
                                glowColor.copy(alpha = auraAlpha * 0.3f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        // 21st.dev Liquid Glass Lens Surface
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
            tint = glowColor.copy(alpha = 0.12f),
            elevation = 24.dp,
            reduceTransparency = a11y.reduceTransparency,
            highContrast = a11y.highContrast,
        ) {
            // Core Visualizer Container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(activeBrush),
                contentAlignment = Alignment.Center,
            ) {
                // Orbital Particle Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val orbitRadius = (size.width / 2f) - 12.dp.toPx()

                    // Draw 3 orbiting luminous particles
                    val particleCount = 3
                    for (i in 0 until particleCount) {
                        val angleDeg = orbitAngle + (i * (360f / particleCount))
                        val angleRad = Math.toRadians(angleDeg.toDouble())
                        val px = cx + (orbitRadius * cos(angleRad)).toFloat()
                        val py = cy + (orbitRadius * sin(angleRad)).toFloat()

                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = 3.dp.toPx(),
                            center = Offset(px, py),
                        )
                        // Particle glow trail
                        drawCircle(
                            color = glowColor.copy(alpha = 0.35f),
                            radius = 7.dp.toPx(),
                            center = Offset(px, py),
                        )
                    }
                }

                // Inner Glass Specular Glare Layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.White.copy(alpha = 0.32f),
                                0.45f to Color.Transparent,
                                1.0f to Color.Black.copy(alpha = 0.35f),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // High-precision vector icon instead of raw emoji
                        when (status.state) {
                            ConnectionState.BONDED -> {
                                VelocityIcons.LightningBolt(
                                    color = Color.White,
                                    size = 38.dp,
                                    filled = true,
                                )
                            }
                            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                                VelocityIcons.RefreshIcon(
                                    color = Color.White,
                                    size = 36.dp,
                                )
                            }
                            ConnectionState.ERROR -> {
                                VelocityIcons.ShieldIcon(
                                    color = Color.White,
                                    size = 36.dp,
                                    filled = true,
                                )
                            }
                            ConnectionState.DISCONNECTED -> {
                                VelocityIcons.PowerIcon(
                                    color = Color.White.copy(alpha = 0.9f),
                                    size = 40.dp,
                                    strokeWidth = 3.dp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = when (status.state) {
                                ConnectionState.BONDED -> "MULTIPATH BONDED"
                                ConnectionState.CONNECTING -> "ESTABLISHING"
                                ConnectionState.RECONNECTING -> "RETRYING"
                                ConnectionState.ERROR -> "TAP TO RETRY"
                                ConnectionState.DISCONNECTED -> "BOND INTERNET"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.3.sp,
                            color = Color.White,
                        )

                        if (status.state == ConnectionState.BONDED) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${((combinedMbps * 10).toInt() / 10.0)} Mbps",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = VelocityColors.CyanGlow,
                            )
                        } else if (status.state == ConnectionState.DISCONNECTED) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Wi-Fi + 5G Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}
