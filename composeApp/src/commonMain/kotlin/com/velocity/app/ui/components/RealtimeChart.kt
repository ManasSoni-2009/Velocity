package com.velocity.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.velocity.app.domain.TelemetrySample
import com.velocity.app.ui.theme.LocalVelocitySemantic

@Composable
fun RealtimeChart(
    samples: List<TelemetrySample>,
    title: String,
    isRttChart: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(semantic.mutedSurface.copy(alpha = 0.55f))
            .border(1.dp, semantic.cardBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = semantic.primaryInk,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChartLegend(color = semantic.wifiTint, label = "Wi-Fi")
                ChartLegend(color = semantic.cellTint, label = "Cell")
                ChartLegend(color = semantic.successInk, label = if (isRttChart) "Avg" else "Total")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (samples.size < 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Live data appears while bonded",
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.secondaryInk,
                )
            }
            return
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp),
        ) {
            val width = size.width
            val height = size.height

            for (i in 0..3) {
                val y = height * (i / 3f)
                drawLine(
                    color = semantic.cardBorder.copy(alpha = 0.65f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f,
                )
            }

            val maxVal = if (isRttChart) {
                samples.maxOfOrNull { maxOf(it.wifiRttMs, it.cellularRttMs, it.effectiveRttMs) }
                    ?.coerceAtLeast(40f) ?: 100f
            } else {
                samples.maxOfOrNull { maxOf(it.wifiDownloadMbps, it.cellularDownloadMbps, it.combinedDownloadMbps) }
                    ?.coerceAtLeast(15f) ?: 50f
            }

            val stepX = width / (samples.size - 1).coerceAtLeast(1)

            fun getY(value: Float): Float {
                val fraction = (value / maxVal).coerceIn(0f, 1f)
                return height - (fraction * (height - 8f)) - 4f
            }

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
                        0.0f to semantic.successInk.copy(alpha = 0.22f),
                        1.0f to Color.Transparent,
                    ),
                )
            }

            val wifiPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.wifiRttMs else s.wifiDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = wifiPath,
                color = semantic.wifiTint,
                style = Stroke(width = 2.4f, cap = StrokeCap.Round),
            )

            val cellPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.cellularRttMs else s.cellularDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = cellPath,
                color = semantic.cellTint,
                style = Stroke(width = 2.4f, cap = StrokeCap.Round),
            )

            val combinedPath = Path().apply {
                samples.forEachIndexed { index, s ->
                    val x = index * stepX
                    val y = getY(if (isRttChart) s.effectiveRttMs else s.combinedDownloadMbps)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = combinedPath,
                color = semantic.successInk,
                style = Stroke(width = 3.2f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ChartLegend(color: Color, label: String) {
    val semantic = LocalVelocitySemantic.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = semantic.secondaryInk,
            fontWeight = FontWeight.Medium,
        )
    }
}
