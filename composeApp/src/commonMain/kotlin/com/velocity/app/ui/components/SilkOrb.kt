package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.ConnectionStatus
import com.velocity.app.domain.NetworkHealthSnapshot
import com.velocity.app.ui.theme.LocalIsDarkTheme
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors

/**
 * Signature tactile hero button for Velocity.
 * Expresses the Pantone Cobalt -> Orchid -> Peach -> Pink silk palette with fluid GPU spring
 * compression, velocity-reactive outer halo, and honest plain-language states.
 */
@Composable
fun SilkOrb(
    status: ConnectionStatus,
    networkHealth: NetworkHealthSnapshot,
    combinedMbps: Double,
    hasServerConfigured: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 210.dp,
) {
    val a11y = LocalVelocityA11y.current
    val isDark = LocalIsDarkTheme.current
    val semantic = LocalVelocitySemantic.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val breath: Float
    val spin: Float
    val ripple: Float

    if (!a11y.reduceMotion && status.state.isActiveOrTransitioning) {
        val infiniteTransition = rememberInfiniteTransition()
        val rawBreath by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        )
        val rawSpin by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
        val rawRipple by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )
        breath = rawBreath
        spin = rawSpin
        ripple = rawRipple
    } else {
        breath = 0.5f
        spin = 0f
        ripple = 0f
    }

    val targetScale = when {
        isPressed -> 0.93f
        status.state == ConnectionState.BONDED && !a11y.reduceMotion -> 1.0f + (breath * 0.025f)
        status.state == ConnectionState.CONNECTING || status.state == ConnectionState.RECONNECTING ->
            0.97f + (breath * 0.04f)
        else -> 1.0f
    }

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    )

    val orbColor by animateColorAsState(
        targetValue = when {
            status.state == ConnectionState.BONDED -> VelocityColors.ImperialViolet
            status.state == ConnectionState.CONNECTING || status.state == ConnectionState.RECONNECTING ->
                VelocityColors.ImperialViolet
            status.state == ConnectionState.ERROR -> VelocityColors.RoyalPlum
            else -> if (isDark) VelocityColors.RoyalPlum else VelocityColors.ImperialViolet
        },
        animationSpec = tween(280),
    )

    val rimColor by animateColorAsState(
        targetValue = when {
            status.state == ConnectionState.BONDED -> VelocityColors.FrostLilac
            status.state == ConnectionState.CONNECTING || status.state == ConnectionState.RECONNECTING ->
                VelocityColors.LuminousLavender
            status.state == ConnectionState.ERROR -> semantic.warningInk
            else -> if (isDark) VelocityColors.LuminousLavender.copy(alpha = 0.55f) else VelocityColors.LuminousLavender
        },
        animationSpec = tween(280),
    )

    val burstProgress = remember { Animatable(1f) }
    var previousState by remember { mutableStateOf(status.state) }
    LaunchedEffect(status.state, a11y.reduceMotion) {
        if (!a11y.reduceMotion && status.state == ConnectionState.BONDED && previousState != ConnectionState.BONDED) {
            burstProgress.snapTo(0f)
            burstProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(720, easing = FastOutSlowInEasing),
            )
        }
        previousState = status.state
    }

    val primaryLabel = when {
        status.state == ConnectionState.BONDED -> "Bonded"
        status.state == ConnectionState.CONNECTING -> "Connecting"
        status.state == ConnectionState.RECONNECTING -> "Retrying"
        status.state == ConnectionState.ERROR -> "Retry"
        !hasServerConfigured -> "Add Relay"
        !networkHealth.hasAnyInternet -> "Offline"
        else -> "Bond"
    }

    val subLabel = when {
        status.state == ConnectionState.BONDED -> formatSpeed(combinedMbps)
        status.state == ConnectionState.CONNECTING -> "Verifying relay"
        status.state == ConnectionState.RECONNECTING -> "Waiting for link"
        status.state == ConnectionState.ERROR -> "Tap to try again"
        !hasServerConfigured -> "Tap to set up"
        !networkHealth.hasAnyInternet -> "No internet"
        networkHealth.onlineLinkCount == 2 -> "Tap to combine"
        else -> "Tap to protect"
    }

    val a11yDescription = "$primaryLabel, $subLabel"

    Box(
        modifier = modifier
            .size(orbSize + 56.dp)
            .semantics {
                role = Role.Button
                contentDescription = a11yDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        // Lightweight outer ring halo (solid tint stroke, zero heavy blurred gradients)
        if (!a11y.reduceTransparency) {
            Box(
                modifier = Modifier
                    .size(orbSize + 24.dp)
                    .graphicsLayer {
                        val s = 0.98f + (breath * 0.05f)
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(VelocityColors.LuminousLavender.copy(alpha = if (status.state == ConnectionState.BONDED) 0.16f else 0.08f)),
            )
        }

        // Expanding velocity-reactive sonar rings + orbital arcs (lightweight vector Canvas)
        if (!a11y.reduceMotion && (status.state.isActiveOrTransitioning || burstProgress.value < 1f)) {
            Canvas(modifier = Modifier.size(orbSize + 56.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val baseR = orbSize.toPx() / 2f
                val maxR = size.width / 2f

                // Celebratory flare burst on entering Bonded state
                if (burstProgress.value < 1f) {
                    val b = burstProgress.value
                    val burstR = baseR + ((maxR - baseR) * 1.25f * b)
                    drawCircle(
                        color = VelocityColors.FrostLilac.copy(alpha = (1f - b) * 0.78f),
                        radius = burstR,
                        center = Offset(cx, cy),
                        style = Stroke(width = (3.5.dp * (1f - b * 0.5f)).toPx()),
                    )
                }

                if (status.state == ConnectionState.BONDED) {
                    val r1 = baseR + ((maxR - baseR) * ripple)
                    val r2Phase = (ripple + 0.5f) % 1f
                    val r2 = baseR + ((maxR - baseR) * r2Phase)
                    drawCircle(
                        color = VelocityColors.LuminousLavender.copy(alpha = (1f - ripple) * 0.65f),
                        radius = r1,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.2.dp.toPx()),
                    )
                    drawCircle(
                        color = VelocityColors.FrostLilac.copy(alpha = (1f - r2Phase) * 0.40f),
                        radius = r2,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.6.dp.toPx()),
                    )
                } else if (status.state == ConnectionState.CONNECTING || status.state == ConnectionState.RECONNECTING) {
                    drawArc(
                        color = VelocityColors.FrostLilac,
                        startAngle = spin,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(cx - baseR - 7.dp.toPx(), cy - baseR - 7.dp.toPx()),
                        size = Size((baseR + 7.dp.toPx()) * 2f, (baseR + 7.dp.toPx()) * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = VelocityColors.LuminousLavender,
                        startAngle = -spin * 1.4f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx - baseR - 14.dp.toPx(), cy - baseR - 14.dp.toPx()),
                        size = Size((baseR + 14.dp.toPx()) * 2f, (baseR + 14.dp.toPx()) * 2f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Solid Imperial Violet Core Button with crisp Frost Lilac rim highlight (no gradients)
        Box(
            modifier = Modifier
                .size(orbSize)
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .clip(CircleShape)
                .background(orbColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 2.5.dp.toPx()
                drawCircle(
                    color = rimColor.copy(alpha = 0.85f),
                    radius = (size.minDimension / 2f) - stroke,
                    style = Stroke(width = stroke),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                when (status.state) {
                    ConnectionState.BONDED -> VelocityIcons.LightningBolt(
                        color = VelocityColors.FrostLilac,
                        size = 34.dp,
                        filled = true,
                    )
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> VelocityIcons.RefreshIcon(
                        color = VelocityColors.FrostLilac,
                        size = 32.dp,
                    )
                    ConnectionState.ERROR -> VelocityIcons.AlertCircleIcon(
                        color = VelocityColors.FrostLilac,
                        size = 34.dp,
                    )
                    ConnectionState.DISCONNECTED -> VelocityIcons.PowerIcon(
                        color = VelocityColors.FrostLilac,
                        size = 34.dp,
                        strokeWidth = 2.8.dp,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = primaryLabel,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = VelocityColors.FrostLilac,
                )

                Spacer(modifier = Modifier.height(4.dp))

                AnimatedContent(
                    targetState = subLabel,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                    },
                ) { text ->
                    Text(
                        text = text,
                        style = if (status.state == ConnectionState.BONDED) {
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = VelocityColors.FrostLilac.copy(alpha = 0.92f),
                    )
                }
            }
        }
    }

    // Touch feedback fallback indicator tint kept clean via semantic tokens
    @Suppress("UNUSED_VARIABLE")
    val unusedSemantic = semantic
}

private fun formatSpeed(mbps: Double): String {
    if (mbps < 0.05) return "Connected"
    val rounded = ((mbps * 10).toInt() / 10.0)
    return "$rounded Mbps"
}
