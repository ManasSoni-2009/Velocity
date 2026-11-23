package com.velocity.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * High-precision vector icons inspired by Apple SF Pro and 21st.dev design systems.
 * Pure vector canvas drawing eliminates emoji pixelation and ensures crisp rendering on all DPIs.
 */
object VelocityIcons {

    @Composable
    fun LightningBolt(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
        filled: Boolean = true,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.58f, h * 0.05f)
                lineTo(w * 0.22f, h * 0.54f)
                lineTo(w * 0.48f, h * 0.54f)
                lineTo(w * 0.40f, h * 0.95f)
                lineTo(w * 0.78f, h * 0.44f)
                lineTo(w * 0.52f, h * 0.44f)
                close()
            }
            if (filled) {
                drawPath(path, color = color, style = Fill)
            } else {
                drawPath(
                    path,
                    color = color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }

    @Composable
    fun PowerIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 24.dp,
        strokeWidth: Dp = 2.5.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val sw = strokeWidth.toPx()

            // Vertical top line
            drawLine(
                color = color,
                start = Offset(w * 0.5f, h * 0.12f),
                end = Offset(w * 0.5f, h * 0.45f),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )

            // Open circle arc (from 225 deg to 315 deg, swept 270 deg)
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(w * 0.18f, h * 0.18f),
                size = Size(w * 0.64f, h * 0.64f),
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
    }

    @Composable
    fun TargetCrosshair(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val stroke = 1.8.dp.toPx()

            // Outer ring
            drawCircle(
                color = color,
                radius = w * 0.38f,
                center = Offset(cx, cy),
                style = Stroke(width = stroke),
            )
            // Center pip
            drawCircle(
                color = color,
                radius = w * 0.12f,
                center = Offset(cx, cy),
                style = Fill,
            )
            // 4 Crosshair ticks
            drawLine(color, Offset(cx, h * 0.05f), Offset(cx, h * 0.20f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(cx, h * 0.80f), Offset(cx, h * 0.95f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.05f, cy), Offset(w * 0.20f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.80f, cy), Offset(w * 0.95f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun ShieldIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
        filled: Boolean = false,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.1f)
                lineTo(w * 0.85f, h * 0.22f)
                cubicTo(w * 0.85f, h * 0.55f, w * 0.68f, h * 0.82f, w * 0.5f, h * 0.92f)
                cubicTo(w * 0.32f, h * 0.82f, w * 0.15f, h * 0.55f, w * 0.15f, h * 0.22f)
                close()
            }
            if (filled) {
                drawPath(path, color = color, style = Fill)
            } else {
                drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            }
        }
    }

    @Composable
    fun AnalyticsChart(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.dp.toPx()

            // 3 Vertical Bars
            drawLine(color, Offset(w * 0.25f, h * 0.85f), Offset(w * 0.25f, h * 0.55f), strokeWidth = stroke * 1.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.50f, h * 0.85f), Offset(w * 0.50f, h * 0.25f), strokeWidth = stroke * 1.5f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.75f, h * 0.85f), Offset(w * 0.75f, h * 0.40f), strokeWidth = stroke * 1.5f, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun GlobeIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val stroke = 1.8.dp.toPx()

            // Outer circle
            drawCircle(color = color, radius = w * 0.42f, center = Offset(cx, cy), style = Stroke(width = stroke))
            // Horizontal equator
            drawLine(color, Offset(w * 0.08f, cy), Offset(w * 0.92f, cy), strokeWidth = stroke)
            // Vertical meridian ellipse
            drawOval(
                color = color,
                topLeft = Offset(w * 0.25f, h * 0.08f),
                size = Size(w * 0.50f, h * 0.84f),
                style = Stroke(width = stroke),
            )
        }
    }

    @Composable
    fun SettingsSliders(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 1.8.dp.toPx()

            // Track 1
            drawLine(color, Offset(w * 0.15f, h * 0.32f), Offset(w * 0.85f, h * 0.32f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.12f, center = Offset(w * 0.38f, h * 0.32f), style = Fill)

            // Track 2
            drawLine(color, Offset(w * 0.15f, h * 0.68f), Offset(w * 0.85f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.12f, center = Offset(w * 0.62f, h * 0.68f), style = Fill)
        }
    }

    @Composable
    fun WifiIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h * 0.78f
            val stroke = 2.dp.toPx()

            // Base point
            drawCircle(color, radius = w * 0.07f, center = Offset(cx, cy), style = Fill)

            // Inner arc
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.28f, h * 0.40f),
                size = Size(w * 0.44f, w * 0.44f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Outer arc
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.10f, h * 0.18f),
                size = Size(w * 0.80f, w * 0.80f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }

    @Composable
    fun CellularIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.2.dp.toPx()

            // 4 Stepped bars
            drawLine(color, Offset(w * 0.20f, h * 0.85f), Offset(w * 0.20f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.40f, h * 0.85f), Offset(w * 0.40f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.60f, h * 0.85f), Offset(w * 0.60f, h * 0.32f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.80f, h * 0.85f), Offset(w * 0.80f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun RefreshIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val stroke = 2.dp.toPx()

            drawArc(
                color = color,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.15f),
                size = Size(w * 0.70f, h * 0.70f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Arrowhead at the end of arc
            val arrowPath = Path().apply {
                moveTo(w * 0.70f, h * 0.22f)
                lineTo(w * 0.86f, h * 0.20f)
                lineTo(w * 0.86f, h * 0.38f)
            }
            drawPath(arrowPath, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
