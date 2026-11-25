package com.velocity.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.BondingUiState
import com.velocity.app.domain.ConnectionState
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.platform.VelocityHaptic
import com.velocity.app.platform.rememberHapticPerformer
import com.velocity.app.ui.components.LinkStatusRow
import com.velocity.app.ui.components.ModeSegmentedControl
import com.velocity.app.ui.components.RelayQuotaBar
import com.velocity.app.ui.components.SilkOrb
import com.velocity.app.ui.components.VelocityIcons
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic

/**
 * Calm, clutter-free primary Bond screen.
 * Surfaces only what a user needs: honest network readiness, one-tap SilkOrb control,
 * simple Wi-Fi & Cellular status pills, and a clean 3-mode selector.
 */
@Composable
fun BondScreen(
    state: BondingUiState,
    onToggleConnect: () -> Unit,
    onSelectMode: (BondingMode) -> Unit,
    onRefreshHealth: () -> Unit,
    onOpenRelays: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val haptic = rememberHapticPerformer()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Editorial Top Header + Live Readiness Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Velocity",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = semantic.primaryInk,
            )

            NetworkReadinessChip(
                state = state,
                onClick = {
                    haptic(VelocityHaptic.TICK)
                    onRefreshHealth()
                },
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Signature SilkOrb Hero Toggle
        SilkOrb(
            status = state.connectionStatus,
            networkHealth = state.networkHealth,
            combinedMbps = state.telemetry.combinedDownloadMbps,
            hasServerConfigured = state.selectedServer != null,
            onClick = {
                haptic(
                    if (state.connectionStatus.state.isActiveOrTransitioning) {
                        VelocityHaptic.TAP
                    } else {
                        VelocityHaptic.CONFIRM
                    },
                )
                onToggleConnect()
            },
            orbSize = 208.dp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Active Relay Pill (1-tap jump to Relays tab)
        RelayChipButton(
            relayName = state.selectedServer?.name,
            isBonded = state.connectionStatus.state == ConnectionState.BONDED,
            onClick = {
                haptic(VelocityHaptic.TAP)
                onOpenRelays()
            },
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Clean Wi-Fi & Cellular Status Pills (+ advisory banner only if a link needs attention)
        LinkStatusRow(
            networkHealth = state.networkHealth,
            telemetry = state.telemetry,
            connectionState = state.connectionStatus.state,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Plain-language 3-mode selector in thumb reach
        ModeSegmentedControl(
            activeMode = state.connectionStatus.activeMode,
            onModeSelected = { mode ->
                haptic(VelocityHaptic.TICK)
                onSelectMode(mode)
            },
        )

        val relayQuota = state.telemetry.relayQuota ?: state.selectedServer?.quota
        if (relayQuota != null && relayQuota.hasValidLimit) {
            Spacer(modifier = Modifier.height(14.dp))
            RelayQuotaBar(quota = relayQuota)
        }

        Spacer(modifier = Modifier.height(104.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun NetworkReadinessChip(
    state: BondingUiState,
    onClick: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current
    val isBonded = state.connectionStatus.state == ConnectionState.BONDED
    val isError = state.connectionStatus.state == ConnectionState.ERROR
    val hasIssue = !state.networkHealth.hasAnyInternet || state.networkHealth.advisoryMessage != null

    val bg = androidx.compose.ui.graphics.Color.Unspecified
    val ink = when {
        isError -> semantic.errorInk
        isBonded -> semantic.successInk
        hasIssue -> semantic.warningInk
        else -> semantic.primaryInk
    }

    val label = when (state.connectionStatus.state) {
        ConnectionState.BONDED -> state.connectionStatus.message
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.RECONNECTING -> state.connectionStatus.message
        ConnectionState.ERROR -> state.connectionStatus.message
        ConnectionState.DISCONNECTED -> state.networkHealth.summaryHeadline
    }

    LiquidGlassSurface(
        cornerRadius = 20.dp,
        tint = bg,
        elevation = 6.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ink),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RelayChipButton(
    relayName: String?,
    isBonded: Boolean,
    onClick: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current

    LiquidGlassSurface(
        cornerRadius = 22.dp,
        elevation = 6.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VelocityIcons.GlobeIcon(
                color = if (isBonded) semantic.accent else semantic.secondaryInk,
                size = 16.dp,
            )
            Text(
                text = if (relayName != null) {
                    if (isBonded) "Relay · $relayName" else relayName
                } else {
                    "No relay configured · Add one"
                },
                style = MaterialTheme.typography.labelLarge,
                color = semantic.primaryInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VelocityIcons.ChevronIcon(
                color = semantic.secondaryInk,
                rotatedDown = false,
                size = 14.dp,
            )
        }
    }
}
