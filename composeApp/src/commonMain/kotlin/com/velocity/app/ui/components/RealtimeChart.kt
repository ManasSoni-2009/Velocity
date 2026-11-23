package com.velocity.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.TelemetrySample
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun RealtimeChart(
    samples: List<TelemetrySample>,
    title: String = "THROUGHPUT HISTORY (MBPS)",
    isRttChart: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardBg = Color(0xFF0F172A).copy(alpha = 0.85f)
    val cardBorder = Color(0xFF1E293B)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            // Legends
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChartLegend(color = VelocityColors.WifiLink, label = "Wi-Fi")
                ChartLegend(color = VelocityColors.CellularLink, label = "Cellular")
                ChartLegend(color = VelocityColors.CombinedGlow, label = if (isRttChart) "Effective" else "Combined")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
        ) {
            val width = size.width
            val height = size.height

            // Draw subtle horizontal grid lines (0%, 33%, 66%, 100%)
            for (i in 0..3) {
                val y = height * (i / 3f)
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f,
                )
            }

            if (samples.size < 2) return@Canvas

            // Find maximum value to normalize scale
            val maxVal = if (isRttChart) {
                samples.maxOfOrNull { maxOf(it.wifiRttMs, it.cellularRttMs, it.effectiveRttMs) }
                    ?.coerceAtLeast(40f) ?: 100f
            } else {
                samples.maxOfOrNull { maxOf(it.wifiDownloadMbps, it.cellularDownloadMbps, it.combinedDownloadMbps) }
                    ?.coerceAtLeast(20f) ?: 50f
            }

            val stepX = width / (samples.size - 1).coerceAtLeast(1)

            fun getY(value: Float): Float {
                val fraction = (value / maxVal).coerceIn(0f, 1f)
                return height - (fraction * (height - 8f)) - 4f
            }

            // Area fill for combined throughput (if throughput chart)
            if (!isRttChart) {
                val fillPath = Path().apply {
                    moveTo(0f, height)
                    samples.forEachIndexed { index, s ->
                        lineTo(index * stepX, getY(s.combinedDownloadMbps))
                    }
                    lineTo((samples.size - 1) * stepX, height)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        0.0f to VelocityColors.CombinedGlow.copy(alpha = 0.25f),
                        1.0f to Color.Transparent,
                    ),
                )
            }

            // Path for Wi-Fi
            val wifiPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.wifiRttMs else s.wifiDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = wifiPath,
                color = VelocityColors.WifiLink.copy(alpha = 0.7f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )

            // Path for Cellular
            val cellularPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.cellularRttMs else s.cellularDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = cellularPath,
                color = VelocityColors.CellularLink.copy(alpha = 0.7f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )

            // Path for Combined
            val combinedPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.effectiveRttMs else s.combinedDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = combinedPath,
                color = VelocityColors.CombinedGlow,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}
