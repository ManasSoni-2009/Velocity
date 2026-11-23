package com.velocity.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
                durationMillis = if (isBonded) 1200 else if (isConnecting) 2000 else 6000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.5f))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MULTIPATH STREAM FLOW",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            Text(
                text = if (isBonded) "Aggregated: ${telemetry.aggregationEfficiencyPercent}% Eff." else "Standby",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isBonded) VelocityColors.CombinedGlow else Color.White.copy(alpha = 0.4f),
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
            val mergeX = width * 0.62f

            // Dashed stroke animation effect
            val dashEffect = if (isBonded || isConnecting) {
                PathEffect.dashPathEffect(floatArrayOf(18f, 12f), streamPhase)
            } else {
                PathEffect.dashPathEffect(floatArrayOf(8f, 16f), 0f)
            }

            // Path 0: Wi-Fi beam (Top curve converging to center)
            val wifiPath = Path().apply {
                moveTo(20f, topY)
                cubicTo(
                    x1 = mergeX * 0.4f, y1 = topY,
                    x2 = mergeX * 0.8f, y2 = midY,
                    x3 = mergeX, y3 = midY,
                )
            }
            drawPath(
                path = wifiPath,
                color = if (telemetry.wifi.active) VelocityColors.WifiLink else VelocityColors.WifiLink.copy(alpha = 0.25f),
                style = Stroke(
                    width = if (isBonded && telemetry.wifi.active) 5f else 3f,
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect,
                ),
            )

            // Path 1: Cellular beam (Bottom curve converging to center)
            val cellularPath = Path().apply {
                moveTo(20f, botY)
                cubicTo(
                    x1 = mergeX * 0.4f, y1 = botY,
                    x2 = mergeX * 0.8f, y2 = midY,
                    x3 = mergeX, y3 = midY,
                )
            }
            drawPath(
                path = cellularPath,
                color = if (telemetry.cellular.active) VelocityColors.CellularLink else VelocityColors.CellularLink.copy(alpha = 0.25f),
                style = Stroke(
                    width = if (isBonded && telemetry.cellular.active) 5f else 3f,
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect,
                ),
            )

            // Combined Tunnel Pipe (from mergeX to end of canvas)
            val combinedPath = Path().apply {
                moveTo(mergeX, midY)
                lineTo(width - 20f, midY)
            }
            drawPath(
                path = combinedPath,
                color = if (isBonded) VelocityColors.CombinedGlow else Color.White.copy(alpha = 0.2f),
                style = Stroke(
                    width = if (isBonded) 8f else 4f,
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect,
                ),
            )

            // Convergence node pulse
            if (isBonded) {
                drawCircle(
                    color = VelocityColors.CyanGlow,
                    radius = 7f,
                    center = Offset(mergeX, midY),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Beam weight ratio indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(VelocityColors.WifiLink),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Wi-Fi: ${telemetry.wifi.schedulerWeightPercent}% (${telemetry.wifi.downloadMbps.toInt()} Mbps)",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(VelocityColors.CellularLink),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Cellular: ${telemetry.cellular.schedulerWeightPercent}% (${telemetry.cellular.downloadMbps.toInt()} Mbps)",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
