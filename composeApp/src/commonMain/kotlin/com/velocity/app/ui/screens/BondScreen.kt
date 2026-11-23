package com.velocity.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.BondingUiState
import com.velocity.app.ui.components.*

@Composable
fun BondScreen(
    state: BondingUiState,
    onToggleConnect: () -> Unit,
    onSelectMode: (BondingMode) -> Unit,
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
        // Mode Selector Floating Capsule
        ModeCapsuleSwitcher(
            activeMode = state.connectionStatus.activeMode,
            onModeSelected = onSelectMode,
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Hero Morphing Liquid Glass Power Orb
        PowerOrb(
            status = state.connectionStatus,
            combinedMbps = state.telemetry.combinedDownloadMbps,
            onClick = onToggleConnect,
            orbSize = 180.dp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Active server indicator
        Text(
            text = "Connected via ${state.selectedServer.name}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Animated Dual-Path Stream Visualizer
        StreamVisualizer(
            status = state.connectionStatus,
            telemetry = state.telemetry,
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Live Wi-Fi & Cellular Link Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinkCard(
                telemetry = state.telemetry.wifi,
                modifier = Modifier.weight(1f),
            )
            LinkCard(
                telemetry = state.telemetry.cellular,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(80.dp)) // Clearance for floating bottom pill bar
    }
}
