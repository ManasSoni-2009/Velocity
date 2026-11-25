package com.velocity.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom geometric vector icons crafted for Velocity.
 * Replaces all emoji icons with crisp, DPI-independent vector paths.
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
                moveTo(w * 0.56f, h * 0.08f)
                lineTo(w * 0.22f, h * 0.54f)
                lineTo(w * 0.48f, h * 0.54f)
                lineTo(w * 0.42f, h * 0.92f)
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
                    style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }

    @Composable
    fun PowerIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 24.dp,
        strokeWidth: Dp = 2.4.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val sw = strokeWidth.toPx()

            drawLine(
                color = color,
                start = Offset(w * 0.5f, h * 0.14f),
                end = Offset(w * 0.5f, h * 0.44f),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )

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
        size: Dp = 18.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val stroke = 1.8.dp.toPx()

            drawCircle(color, radius = w * 0.36f, center = Offset(cx, cy), style = Stroke(width = stroke))
            drawCircle(color, radius = w * 0.12f, center = Offset(cx, cy), style = Fill)
            drawLine(color, Offset(cx, h * 0.08f), Offset(cx, h * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(cx, h * 0.78f), Offset(cx, h * 0.92f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.08f, cy), Offset(w * 0.22f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.78f, cy), Offset(w * 0.92f, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun ShieldIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
        filled: Boolean = false,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.10f)
                lineTo(w * 0.84f, h * 0.23f)
                cubicTo(w * 0.84f, h * 0.56f, w * 0.68f, h * 0.82f, w * 0.5f, h * 0.91f)
                cubicTo(w * 0.32f, h * 0.82f, w * 0.16f, h * 0.56f, w * 0.16f, h * 0.23f)
                close()
            }
            if (filled) {
                drawPath(path, color = color, style = Fill)
            } else {
                drawPath(path, color = color, style = Stroke(width = 1.9.dp.toPx(), join = StrokeJoin.Round))
            }
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

            drawCircle(color = color, radius = w * 0.40f, center = Offset(cx, cy), style = Stroke(width = stroke))
            drawLine(color, Offset(w * 0.10f, cy), Offset(w * 0.90f, cy), strokeWidth = stroke)
            drawOval(
                color = color,
                topLeft = Offset(w * 0.28f, h * 0.10f),
                size = Size(w * 0.44f, h * 0.80f),
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

            drawLine(color, Offset(w * 0.14f, h * 0.33f), Offset(w * 0.86f, h * 0.33f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.36f, h * 0.33f), style = Fill)

            drawLine(color, Offset(w * 0.14f, h * 0.67f), Offset(w * 0.86f, h * 0.67f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.64f, h * 0.67f), style = Fill)
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
            val stroke = 2.0.dp.toPx()

            drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.78f), style = Fill)
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.28f, h * 0.42f),
                size = Size(w * 0.44f, w * 0.44f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.11f, h * 0.21f),
                size = Size(w * 0.78f, w * 0.78f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }

    @Composable
    fun CellularIcon(
        color: Color,
        activeBars: Int = 4,
        modifier: Modifier = Modifier,
        size: Dp = 20.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.2.dp.toPx()
            val heights = listOf(0.68f, 0.52f, 0.35f, 0.18f)
            val xs = listOf(0.20f, 0.40f, 0.60f, 0.80f)

            for (i in 0..3) {
                val barColor = if (i < activeBars) color else color.copy(alpha = 0.24f)
                drawLine(
                    color = barColor,
                    start = Offset(w * xs[i], h * 0.84f),
                    end = Offset(w * xs[i], h * heights[i]),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
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
            val stroke = 2.dp.toPx()

            drawArc(
                color = color,
                startAngle = 40f,
                sweepAngle = 275f,
                useCenter = false,
                topLeft = Offset(w * 0.16f, h * 0.16f),
                size = Size(w * 0.68f, h * 0.68f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val arrowPath = Path().apply {
                moveTo(w * 0.68f, h * 0.18f)
                lineTo(w * 0.86f, h * 0.18f)
                lineTo(w * 0.86f, h * 0.36f)
            }
            drawPath(
                arrowPath,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }

    @Composable
    fun AlertCircleIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 1.9.dp.toPx()

            drawCircle(color, radius = w * 0.40f, center = Offset(w / 2f, h / 2f), style = Stroke(width = stroke))
            drawLine(color, Offset(w * 0.5f, h * 0.28f), Offset(w * 0.5f, h * 0.54f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.70f), style = Fill)
        }
    }

    @Composable
    fun CheckIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 16.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.1.dp.toPx()
            val path = Path().apply {
                moveTo(w * 0.20f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.82f, h * 0.28f)
            }
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    @Composable
    fun PlusIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.1.dp.toPx()
            drawLine(color, Offset(w * 0.5f, h * 0.20f), Offset(w * 0.5f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.20f, h * 0.5f), Offset(w * 0.80f, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    @Composable
    fun TrashIcon(
        color: Color,
        modifier: Modifier = Modifier,
        size: Dp = 18.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 1.7.dp.toPx()
            drawLine(color, Offset(w * 0.20f, h * 0.26f), Offset(w * 0.80f, h * 0.26f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.28f, h * 0.26f),
                size = Size(w * 0.44f, h * 0.56f),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = stroke),
            )
        }
    }

    @Composable
    fun ChevronIcon(
        color: Color,
        rotatedDown: Boolean = false,
        modifier: Modifier = Modifier,
        size: Dp = 16.dp,
    ) {
        Canvas(modifier = modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 2.dp.toPx()
            val path = Path().apply {
                if (rotatedDown) {
                    moveTo(w * 0.24f, h * 0.36f)
                    lineTo(w * 0.50f, h * 0.64f)
                    lineTo(w * 0.76f, h * 0.36f)
                } else {
                    moveTo(w * 0.36f, h * 0.24f)
                    lineTo(w * 0.64f, h * 0.50f)
                    lineTo(w * 0.36f, h * 0.76f)
                }
            }
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
