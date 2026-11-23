package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.SpeedTestProgress
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun SpeedTestRunner(
    speedTest: SpeedTestProgress,
    onStartTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardBg = Color(0xFF101827).copy(alpha = 0.9f)
    val cardBorder = Color(0xFF1F2937)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "MULTIPATH SPEED BENCHMARK",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Text(
                    text = if (speedTest.isRunning) speedTest.phase else "Dual-Path Aggregation Test",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }

            Button(
                onClick = onStartTest,
                enabled = !speedTest.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VelocityColors.ElectricBlue,
                    contentColor = Color.White,
                    disabledContainerColor = VelocityColors.ElectricBlue.copy(alpha = 0.4f),
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (speedTest.isRunning) "Testing..." else if (speedTest.result != null) "Retest" else "Run Test",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (speedTest.isRunning) {
            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { speedTest.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = VelocityColors.CyanGlow,
                trackColor = Color(0xFF1E293B),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${((speedTest.liveCombinedMbps * 10).toInt() / 10.0)}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VelocityColors.CyanGlow,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Mbps",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        speedTest.result?.let { res ->
            Spacer(modifier = Modifier.height(14.dp))
            // Result Summary Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A))
                    .padding(12.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Aggregation Gain",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(VelocityColors.CombinedGlow.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "+${res.gainPercent}% Bandwidth",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VelocityColors.CombinedGlow,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ResultMetric(
                            label = "Wi-Fi Only",
                            mbps = res.wifiDownloadMbps,
                            rtt = res.wifiRttMs,
                            color = VelocityColors.WifiLink,
                        )
                        ResultMetric(
                            label = "Cellular Only",
                            mbps = res.cellularDownloadMbps,
                            rtt = res.cellularRttMs,
                            color = VelocityColors.CellularLink,
                        )
                        ResultMetric(
                            label = "Combined Bond",
                            mbps = res.combinedDownloadMbps,
                            rtt = res.combinedRttMs,
                            color = VelocityColors.CombinedGlow,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = speedTest.errorMessage != null) {
            speedTest.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Error: $err",
                    fontSize = 11.sp,
                    color = VelocityColors.StatusError,
                )
            }
        }
    }
}

@Composable
private fun ResultMetric(label: String, mbps: Double, rtt: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${mbps.toInt()} Mbps",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = "${rtt.toInt()} ms",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}
