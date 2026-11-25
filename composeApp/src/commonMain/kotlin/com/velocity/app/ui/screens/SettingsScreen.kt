package com.velocity.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.*
import com.velocity.app.platform.LiquidGlassSurface
import com.velocity.app.platform.VelocityHaptic
import com.velocity.app.platform.rememberHapticPerformer
import com.velocity.app.ui.components.RealtimeChart
import com.velocity.app.ui.components.SpeedTestRunner
import com.velocity.app.ui.components.VelocityIcons
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: BondingUiState,
    onUpdateRule: (packageName: String, policy: TunnelRulePolicy) -> Unit,
    onUpdateSettings: ((VelocitySettings) -> VelocitySettings) -> Unit,
    onStartSpeedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVelocitySemantic.current
    val haptic = rememberHapticPerformer()
    val scrollState = rememberScrollState()

    var showDiagnostics by remember { mutableStateOf(false) }
    var showAppRulesSheet by remember { mutableStateOf(false) }

    val bypassedCount = remember(state.appRules) {
        state.appRules.count { it.policy == TunnelRulePolicy.BYPASS }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = semantic.primaryInk,
        )

        // Section 1: Smart Network Preferences
        SettingsGroupCard(title = "Network") {
            SettingToggleRow(
                title = "Auto-skip bad Wi-Fi",
                subtitle = "Automatically fall back to Cellular if Wi-Fi loses internet",
                checked = state.settings.autoFallbackOnNoInternet,
                onCheckedChange = { checked ->
                    haptic(VelocityHaptic.TICK)
                    onUpdateSettings { it.copy(autoFallbackOnNoInternet = checked) }
                },
            )

            HorizontalDivider(color = semantic.cardBorder.copy(alpha = 0.6f))

            // Wi-Fi Preference Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Prefer Wi-Fi balance",
                        style = MaterialTheme.typography.titleMedium,
                        color = semantic.primaryInk,
                    )
                    Text(
                        text = "${state.settings.wifiBiasPercent}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = semantic.accent,
                    )
                }
                Slider(
                    value = state.settings.wifiBiasPercent.toFloat(),
                    onValueChange = { v ->
                        val snapped = ((v / 5f).roundToInt() * 5).coerceIn(10, 90)
                        onUpdateSettings { it.copy(wifiBiasPercent = snapped) }
                    },
                    valueRange = 10f..90f,
                    colors = SliderDefaults.colors(
                        thumbColor = semantic.accent,
                        activeTrackColor = semantic.accent,
                        inactiveTrackColor = semantic.mutedSurface,
                    ),
                )
            }

            HorizontalDivider(color = semantic.cardBorder.copy(alpha = 0.6f))

            // Cellular Data Cap Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Cellular data limit",
                        style = MaterialTheme.typography.titleMedium,
                        color = semantic.primaryInk,
                    )
                    Text(
                        text = "${state.settings.cellularDataCapMb / 1024} GB",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = semantic.cellTint,
                    )
                }
                Slider(
                    value = (state.settings.cellularDataCapMb / 1024).toFloat(),
                    onValueChange = { v ->
                        val gb = v.roundToInt().coerceIn(1, 30)
                        onUpdateSettings { it.copy(cellularDataCapMb = gb * 1024) }
                    },
                    valueRange = 1f..30f,
                    colors = SliderDefaults.colors(
                        thumbColor = semantic.cellTint,
                        activeTrackColor = semantic.cellTint,
                        inactiveTrackColor = semantic.mutedSurface,
                    ),
                )
            }

            HorizontalDivider(color = semantic.cardBorder.copy(alpha = 0.6f))

            // App Bypass Navigation Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptic(VelocityHaptic.TAP)
                        showAppRulesSheet = true
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "App routing",
                        style = MaterialTheme.typography.titleMedium,
                        color = semantic.primaryInk,
                    )
                    Text(
                        text = when (bypassedCount) {
                            0 -> "All apps bonded"
                            1 -> "1 app bypassed"
                            else -> "$bypassedCount apps bypassed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.secondaryInk,
                    )
                }
                VelocityIcons.ChevronIcon(color = semantic.secondaryInk, size = 16.dp)
            }
        }

        // Section 2: Appearance & Accessibility
        SettingsGroupCard(title = "Appearance") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Color theme",
                    style = MaterialTheme.typography.titleMedium,
                    color = semantic.primaryInk,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(semantic.mutedSurface)
                        .padding(4.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = state.settings.themeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) semantic.accent else Color.Transparent)
                                .clickable {
                                    haptic(VelocityHaptic.TICK)
                                    onUpdateSettings { it.copy(themeMode = mode) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) semantic.onAccent else semantic.primaryInk,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = semantic.cardBorder.copy(alpha = 0.6f))

            SettingToggleRow(
                title = "Reduce motion",
                subtitle = "Minimizes ambient animations and transitions",
                checked = state.settings.reduceMotion,
                onCheckedChange = { checked ->
                    haptic(VelocityHaptic.TICK)
                    onUpdateSettings { it.copy(reduceMotion = checked) }
                },
            )

            HorizontalDivider(color = semantic.cardBorder.copy(alpha = 0.6f))

            SettingToggleRow(
                title = "Reduce transparency",
                subtitle = "Uses solid high-opacity cards and controls",
                checked = state.settings.reduceTransparency,
                onCheckedChange = { checked ->
                    haptic(VelocityHaptic.TICK)
                    onUpdateSettings { it.copy(reduceTransparency = checked) }
                },
            )
        }

        // Section 3: Collapsible Diagnostics & Speed Test Drawer (Collapsed by default)
        val a11y = LocalVelocityA11y.current
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            elevation = 6.dp,
            reduceTransparency = a11y.reduceTransparency,
            highContrast = a11y.highContrast,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable {
                        haptic(VelocityHaptic.TAP)
                        showDiagnostics = !showDiagnostics
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Diagnostics & Speed Test",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = semantic.primaryInk,
                    )
                    Text(
                        text = "Live throughput charts, benchmark & packet stats",
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.secondaryInk,
                    )
                }
                VelocityIcons.ChevronIcon(
                    color = semantic.accent,
                    rotatedDown = showDiagnostics,
                    size = 18.dp,
                )
            }

            AnimatedVisibility(
                visible = showDiagnostics,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeedTestRunner(
                        speedTest = state.speedTest,
                        onStartTest = onStartSpeedTest,
                    )

                    RealtimeChart(
                        samples = state.telemetryHistory,
                        title = "Throughput (Mbps)",
                        isRttChart = false,
                    )

                    RealtimeChart(
                        samples = state.telemetryHistory,
                        title = "Latency (ms)",
                        isRttChart = true,
                    )

                    EngineDiagnosticsGrid(state = state)

                    // Advanced Engine Toggles (tucked inside Diagnostics)
                    SettingToggleRow(
                        title = "Packet loss recovery (FEC)",
                        subtitle = "Adds lightweight XOR parity on jittery links",
                        checked = state.settings.fecEnabled,
                        onCheckedChange = { checked ->
                            onUpdateSettings { it.copy(fecEnabled = checked) }
                        },
                    )
                    SettingToggleRow(
                        title = "Direct DNS fallback",
                        subtitle = "Resolves DNS safely during handshake transitions",
                        checked = state.settings.directDnsGuard,
                        onCheckedChange = { checked ->
                            onUpdateSettings { it.copy(directDnsGuard = checked) }
                        },
                    )
                }
            }
        }
        }

        Spacer(modifier = Modifier.height(104.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }

    if (showAppRulesSheet) {
        AppRoutingBottomSheet(
            rules = state.appRules,
            onUpdateRule = onUpdateRule,
            onDismiss = { showAppRulesSheet = false },
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    val a11y = LocalVelocityA11y.current
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        elevation = 6.dp,
        reduceTransparency = a11y.reduceTransparency,
        highContrast = a11y.highContrast,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = semantic.accent,
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = semantic.primaryInk,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = semantic.secondaryInk,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = semantic.onAccent,
                checkedTrackColor = semantic.accent,
                uncheckedThumbColor = semantic.secondaryInk,
                uncheckedTrackColor = semantic.mutedSurface,
            ),
        )
    }
}

@Composable
private fun EngineDiagnosticsGrid(state: BondingUiState) {
    val semantic = LocalVelocitySemantic.current
    val tel = state.telemetry

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(semantic.mutedSurface.copy(alpha = 0.55f))
            .border(1.dp, semantic.cardBorder, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Session Engine Details",
            style = MaterialTheme.typography.labelLarge,
            color = semantic.primaryInk,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DiagCell("Reorder Window", "${tel.reorderTimeoutMs} ms")
            DiagCell("FEC Recovered", "${tel.fecRecoveredPackets} pkts")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DiagCell("Session Rx", formatBytes(tel.sessionBytesRx))
            DiagCell("Cellular Used", "${((tel.cellularBytesUsedMb * 10).toInt() / 10.0)} MB")
        }
    }
}

@Composable
private fun DiagCell(label: String, value: String) {
    val semantic = LocalVelocitySemantic.current
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = semantic.secondaryInk)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = semantic.primaryInk,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoutingBottomSheet(
    rules: List<AppTunnelRule>,
    onUpdateRule: (packageName: String, policy: TunnelRulePolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalVelocitySemantic.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(rules, query) {
        if (query.isBlank()) rules else {
            rules.filter {
                it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = semantic.card,
        contentColor = semantic.primaryInk,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "App Routing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = semantic.primaryInk,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (rules.isEmpty()) "No device apps found" else "No matching apps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.secondaryInk,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(semantic.mutedSurface.copy(alpha = 0.65f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = semantic.primaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = semantic.secondaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TunnelRulePolicy.entries.forEach { policy ->
                                    val selected = app.policy == policy
                                    Box(
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) semantic.accent else Color.Transparent)
                                            .clickable { onUpdateRule(app.packageName, policy) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = policy.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) semantic.onAccent else semantic.secondaryInk,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
