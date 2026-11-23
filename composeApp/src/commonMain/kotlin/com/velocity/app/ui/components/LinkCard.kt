package com.velocity.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.LinkTelemetry
import com.velocity.app.domain.LinkType
import com.velocity.app.ui.theme.VelocityColors

/**
 * 21st.dev inspired Frosted Glass Network Link Card.
 * Displays live physical path telemetry (RTT, Jitter, Loss, Signal) with
 * specular glass borders and dedicated vector network icons.
 */
@Composable
fun LinkCard(
    telemetry: LinkTelemetry,
    modifier: Modifier = Modifier,
) {
    val isWifi = telemetry.type == LinkType.WIFI
    val accentColor = if (isWifi) VelocityColors.WifiLink else VelocityColors.CellularLink
    val cardBg = Color(0xFF0F172A).copy(alpha = 0.85f)
    val cardBorder = if (telemetry.active) {
        Brush.verticalGradient(
            listOf(accentColor.copy(alpha = 0.65f), accentColor.copy(alpha = 0.20f)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFF334155).copy(alpha = 0.5f), Color(0xFF1E293B).copy(alpha = 0.3f)),
        )
    }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                // Vector icon
                if (isWifi) {
                    VelocityIcons.WifiIcon(
                        color = if (telemetry.active) accentColor else Color.Gray,
                        size = 18.dp,
                    )
                } else {
                    VelocityIcons.CellularIcon(
                        color = if (telemetry.active) accentColor else Color.Gray,
                        size = 18.dp,
                    )
                }

                Text(
                    text = if (isWifi) "Wi-Fi" else "Cellular",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                )

                if (!isWifi) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(VelocityColors.CellularLink.copy(alpha = 0.25f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "5G",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = VelocityColors.CellularLink,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (telemetry.isMetered) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF374151))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "Cap",
                            fontSize = 8.sp,
                            color = Color(0xFFFBBF24),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = if (telemetry.active) "${telemetry.downloadMbps.toInt()} Mbps" else "Standby",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (telemetry.active) accentColor else Color.White.copy(alpha = 0.4f),
                maxLines = 1,
                softWrap = false,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Metrics Grid (RTT, Jitter, Loss, Signal)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricPill(
                label = "RTT",
                value = if (telemetry.active) "${telemetry.rttMs.toInt()} ms" else "--",
            )
            MetricPill(
                label = "Jitter",
                value = if (telemetry.active) "${telemetry.jitterMs.toInt()} ms" else "--",
            )
            MetricPill(
                label = "Loss",
                value = if (telemetry.active) "${((telemetry.lossPercent * 10).toInt() / 10.0)}%" else "0%",
            )
            MetricPill(
                label = "Signal",
                value = if (telemetry.signalDbm != -120) "${telemetry.signalDbm} dBm" else "--",
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scheduler Allocation Progress Bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "BDP Path Share",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Text(
                    text = "${telemetry.schedulerWeightPercent}%",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1E293B)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (telemetry.schedulerWeightPercent / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor),
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B).copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.45f),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f),
        )
    }
}
