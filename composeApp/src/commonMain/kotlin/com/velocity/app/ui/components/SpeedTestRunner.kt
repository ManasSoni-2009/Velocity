package com.velocity.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.SpeedTestProgress
import com.velocity.app.ui.theme.LocalVelocitySemantic

@Composable
fun SpeedTestRunner(
    speedTest: SpeedTestProgress,
    onStartTest: () -> Unit,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Speed Benchmark",
                    style = MaterialTheme.typography.titleMedium,
                    color = semantic.primaryInk,
                )
                Text(
                    text = if (speedTest.isRunning) speedTest.phase else "Tests Wi-Fi, Cellular & combined throughput",
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.secondaryInk,
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onStartTest,
                enabled = !speedTest.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = semantic.accent,
                    contentColor = semantic.onAccent,
                    disabledContainerColor = semantic.accent.copy(alpha = 0.4f),
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(
                    text = when {
                        speedTest.isRunning -> "Testing…"
                        speedTest.result != null -> "Retest"
                        else -> "Run Test"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (speedTest.isRunning) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { speedTest.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = semantic.accent,
                trackColor = semantic.cardBorder,
            )
            if (speedTest.liveCombinedMbps > 0.1) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${((speedTest.liveCombinedMbps * 10).toInt() / 10.0)} Mbps",
                    style = MaterialTheme.typography.headlineMedium,
                    color = semantic.accent,
                )
            }
        }

        speedTest.result?.let { res ->
            Spacer(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(semantic.card)
                    .border(1.dp, semantic.cardBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Bonded Gain",
                        style = MaterialTheme.typography.labelLarge,
                        color = semantic.primaryInk,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(semantic.successBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = if (res.gainPercent > 0) "+${res.gainPercent}% faster" else "Measured",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = semantic.successInk,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BenchmarkStat("Wi-Fi", res.wifiDownloadMbps, res.wifiRttMs, semantic.wifiTint)
                    BenchmarkStat("Cellular", res.cellularDownloadMbps, res.cellularRttMs, semantic.cellTint)
                    BenchmarkStat("Combined", res.combinedDownloadMbps, res.combinedRttMs, semantic.successInk)
                }
            }
        }

        AnimatedVisibility(visible = speedTest.errorMessage != null) {
            speedTest.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(semantic.errorBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VelocityIcons.AlertCircleIcon(color = semantic.errorInk, size = 16.dp)
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.errorInk,
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchmarkStat(label: String, mbps: Double, rttMs: Double, accent: Color) {
    val semantic = LocalVelocitySemantic.current
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = semantic.secondaryInk,
        )
        Text(
            text = if (mbps > 0.05) "${((mbps * 10).toInt() / 10.0)} Mbps" else "—",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Text(
            text = if (rttMs > 0.1) "${rttMs.toInt()} ms" else "No reply",
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = semantic.secondaryInk,
        )
    }
}
