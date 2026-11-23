package com.velocity.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingUiState
import com.velocity.app.ui.components.RealtimeChart
import com.velocity.app.ui.components.SpeedTestRunner
import com.velocity.app.ui.theme.VelocityColors

@Composable
fun TelemetryScreen(
    state: BondingUiState,
    onStartSpeedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Speed Test Benchmark
        SpeedTestRunner(
            speedTest = state.speedTest,
            onStartTest = onStartSpeedTest,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Realtime Throughput Chart
        RealtimeChart(
            samples = state.telemetryHistory,
            title = "THROUGHPUT HISTORY (MBPS)",
            isRttChart = false,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Realtime RTT & Jitter Chart
        RealtimeChart(
            samples = state.telemetryHistory,
            title = "LATENCY HISTORY (RTT MS)",
            isRttChart = true,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Multi-link Multipath Scheduling & FEC Diagnostics Card
        MultipathDiagnosticsCard(state = state)

        Spacer(modifier = Modifier.height(80.dp)) // Clearance for floating bottom pill bar
    }
}

@Composable
private fun MultipathDiagnosticsCard(state: BondingUiState) {
    val cardBg = Color(0xFF101827).copy(alpha = 0.9f)
    val cardBorder = Color(0xFF1F2937)
    val tel = state.telemetry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "PROTOCOL & FEC ENGINE STATS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DiagnosticItem(
                label = "Reorder Buffer",
                value = "${tel.reorderBufferDepth} pkts",
                detail = "Adaptive Clamp: ${tel.reorderTimeoutMs} ms",
            )
            DiagnosticItem(
                label = "FEC Recovery",
                value = "${tel.fecRecoveredPackets} pkts",
                detail = "Sent: ${tel.fecSentPackets} parity",
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DiagnosticItem(
                label = "Duplicated",
                value = "${tel.duplicatedPackets} pkts",
                detail = "Low-latency mode",
            )
            DiagnosticItem(
                label = "Aggregation Eff.",
                value = "${tel.aggregationEfficiencyPercent}%",
                detail = "Ideal: > 85%",
                highlightColor = VelocityColors.CombinedGlow,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DiagnosticItem(
                label = "Total Download",
                value = formatBytes(tel.sessionBytesRx),
                detail = "Rx session total",
            )
            DiagnosticItem(
                label = "Cellular Used",
                value = "${((tel.cellularBytesUsedMb * 10).toInt() / 10.0)} MB",
                detail = "Cap: ${state.settings.cellularDataCapMb} MB",
            )
        }
    }
}

@Composable
private fun DiagnosticItem(
    label: String,
    value: String,
    detail: String,
    highlightColor: Color = Color.White,
) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = highlightColor,
        )
        Text(
            text = detail,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        val gb = mb / 1024.0
        "${((gb * 100).toInt() / 100.0)} GB"
    } else {
        "${((mb * 10).toInt() / 10.0)} MB"
    }
}
