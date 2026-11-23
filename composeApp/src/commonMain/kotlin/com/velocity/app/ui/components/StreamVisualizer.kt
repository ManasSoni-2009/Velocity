package com.velocity.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.CombinedTelemetry
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.ConnectionStatus
import com.velocity.app.ui.theme.VelocityColors

/**
 * 21st.dev inspired Multipath Stream Convergence Visualizer.
 * Displays dynamic animated packet flow from independent Wi-Fi and Cellular channels
 * converging into a single bonded super-pipe with live aggregation efficiency.
 */
@Composable
fun StreamVisualizer(
    status: ConnectionStatus,
    telemetry: CombinedTelemetry,
    modifier: Modifier = Modifier,
) {
    val isBonded = status.state == ConnectionState.BONDED
    val isConnecting = status.state == ConnectionState.CONNECTING || status.state == ConnectionState.RECONNECTING

    val infiniteTransition = rememberInfiniteTransition()
    val streamPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isBonded) 1000 else if (isConnecting) 1800 else 6000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
    )

    val cardBorder = Brush.verticalGradient(
        listOf(Color(0xFF334155).copy(alpha = 0.6f), Color(0xFF0F172A).copy(alpha = 0.3f)),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.85f))
            .border(1.dp, cardBorder, RoundedCornerShape(22.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VelocityIcons.LightningBolt(
                    color = VelocityColors.CyanGlow,
                    size = 14.dp,
                    filled = true,
                )
                Text(
                    text = "MULTIPATH STREAM ENGINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            Text(
                text = if (isBonded) "Efficiency: ${telemetry.aggregationEfficiencyPercent}%" else "Standby",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isBonded) VelocityColors.CombinedGlow else Color.White.copy(alpha = 0.45f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Dual-path Convergence Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
        ) {
            val width = size.width
            val height = size.height
            val midY = height / 2f
            val topY = height * 0.22f
            val botY = height * 0.78f
            val mergeX = width * 0.60f

            // Dashed stroke animation effect
            val dashEffect = if (isBonded || isConnecting) {
                PathEffect.dashPathEffect(floatArrayOf(16f, 10f), streamPhase)
            } else {
                PathEffect.dashPathEffect(floatArrayOf(8f, 16f), 0f)
            }

            // Path 0: Wi-Fi stream (Top curve converging to center)
            val wifiPath = Path().apply {
                moveTo(24f, topY)
                cubicTo(
                    mergeX * 0.4f, topY,
                    mergeX * 0.8f, midY,
                    mergeX, midY,
                )
            }

            // Path 1: Cellular stream (Bottom curve converging to center)
            val cellPath = Path().apply {
                moveTo(24f, botY)
                cubicTo(
                    mergeX * 0.4f, botY,
                    mergeX * 0.8f, midY,
                    mergeX, midY,
                )
            }

            // Path 2: Combined Bonded stream (Out to right edge)
            val bondedPath = Path().apply {
                moveTo(mergeX, midY)
                lineTo(width - 24f, midY)
            }

            // Inactive guide rails
            drawPath(
                wifiPath,
                color = VelocityColors.WifiLink.copy(alpha = 0.18f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                cellPath,
                color = VelocityColors.CellularLink.copy(alpha = 0.18f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                bondedPath,
                color = VelocityColors.CombinedGlow.copy(alpha = 0.22f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )

            // Animated dynamic pulses
            if (isBonded || isConnecting) {
                drawPath(
                    wifiPath,
                    brush = VelocityColors.WifiBeamBrush,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = dashEffect,
                        cap = StrokeCap.Round,
                    ),
                )
                drawPath(
                    cellPath,
                    brush = VelocityColors.CellularBeamBrush,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = dashEffect,
                        cap = StrokeCap.Round,
                    ),
                )
                if (isBonded) {
                    drawPath(
                        bondedPath,
                        brush = VelocityColors.CombinedBeamBrush,
                        style = Stroke(
                            width = 5.dp.toPx(),
                            pathEffect = dashEffect,
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }

            // Channel Origin and Convergence Nodes
            drawCircle(
                color = VelocityColors.WifiLink,
                radius = 4.dp.toPx(),
                center = Offset(24f, topY),
            )
            drawCircle(
                color = VelocityColors.CellularLink,
                radius = 4.dp.toPx(),
                center = Offset(24f, botY),
            )
            drawCircle(
                color = if (isBonded) VelocityColors.CombinedGlow else Color.Gray,
                radius = 6.dp.toPx(),
                center = Offset(mergeX, midY),
            )
        }
    }
}
