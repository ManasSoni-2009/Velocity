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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.BondingUiState
import com.velocity.app.domain.ConnectionState
import com.velocity.app.ui.components.*
import com.velocity.app.ui.theme.VelocityColors

/**
 * 21st.dev inspired Main Bonding Dashboard.
 * Showcases the interactive multi-layer power orb, liquid glass mode switcher,
 * live multipath convergence visualizer, and dual link telemetry.
 */
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

        Spacer(modifier = Modifier.height(16.dp))

        // Hero 21st.dev Morphing Liquid Glass Power Orb
        PowerOrb(
            status = state.connectionStatus,
            combinedMbps = state.telemetry.combinedDownloadMbps,
            onClick = onToggleConnect,
            orbSize = 186.dp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Active Server & DNS Guard Status Pill Card
        ServerStatusInfoPill(state = state)

        Spacer(modifier = Modifier.height(18.dp))

        // Animated Dual-Path Stream Convergence Visualizer
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

        Spacer(modifier = Modifier.height(84.dp)) // Clearance for floating bottom pill bar
    }
}

@Composable
private fun ServerStatusInfoPill(state: BondingUiState) {
    val isBonded = state.connectionStatus.state == ConnectionState.BONDED
    val pillBorder = Brush.verticalGradient(
        listOf(Color(0xFF334155).copy(alpha = 0.5f), Color(0xFF1E293B).copy(alpha = 0.2f)),
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.7f))
            .border(1.dp, pillBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VelocityIcons.GlobeIcon(
            color = if (isBonded) VelocityColors.CyanGlow else Color.White.copy(alpha = 0.6f),
            size = 14.dp,
        )

        Text(
            text = if (isBonded) "Relay: ${state.selectedServer.name}" else "Selected: ${state.selectedServer.name}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.85f),
        )

        Text(
            text = "•",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
        )

        Text(
            text = "Direct DNS Guard",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = VelocityColors.CombinedGlow,
        )
    }
}
