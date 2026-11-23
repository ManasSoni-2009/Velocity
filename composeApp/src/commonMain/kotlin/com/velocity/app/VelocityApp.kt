package com.velocity.app

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velocity.app.domain.BondingViewModel
import com.velocity.app.domain.VelocityTab
import com.velocity.app.platform.VelocityPlatformBridge
import com.velocity.app.ui.components.FloatingNavBar
import com.velocity.app.ui.components.TopFloatingStatusPill
import com.velocity.app.ui.screens.BondScreen
import com.velocity.app.ui.screens.ServersScreen
import com.velocity.app.ui.screens.SettingsScreen
import com.velocity.app.ui.screens.TelemetryScreen
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.VelocityTheme
import kotlinx.coroutines.delay

@Composable
fun VelocityApp(
    bridge: VelocityPlatformBridge,
    viewModel: BondingViewModel = remember(bridge) { BondingViewModel(bridge) },
) {
    val state by viewModel.uiState.collectAsState()

    VelocityTheme(
        themeMode = state.settings.themeMode,
        reduceTransparency = state.settings.reduceTransparency,
        highContrast = state.settings.highContrast,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VelocityColors.DarkCanvas),
        ) {
            // Main Content Layer
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Top Floating Status Pill
                TopFloatingStatusPill(
                    status = state.connectionStatus,
                    combinedMbps = state.telemetry.combinedDownloadMbps,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Tab Content Switcher with Crossfade
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Crossfade(
                        targetState = state.currentTab,
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        when (tab) {
                            VelocityTab.BOND -> BondScreen(
                                state = state,
                                onToggleConnect = { viewModel.toggleConnect() },
                                onSelectMode = { viewModel.selectMode(it) },
                            )
                            VelocityTab.TELEMETRY -> TelemetryScreen(
                                state = state,
                                onStartSpeedTest = { viewModel.startSpeedTest() },
                            )
                            VelocityTab.SERVERS -> ServersScreen(
                                state = state,
                                onSelectServer = { viewModel.selectServer(it) },
                                onOpenImportDialog = { viewModel.openImportDialog() },
                                onCloseImportDialog = { viewModel.closeImportDialog() },
                                onImportUri = { viewModel.importServerUri(it) },
                                onProbeServers = { viewModel.probeAllServers() },
                                onCopySnippet = { label, text -> viewModel.copyToClipboard(label, text) },
                            )
                            VelocityTab.SETTINGS -> SettingsScreen(
                                state = state,
                                onUpdateRule = { pkg, policy -> viewModel.updateAppRule(pkg, policy) },
                                onUpdateSettings = { transform -> viewModel.updateSettings(transform) },
                            )
                        }
                    }
                }
            }

            // Floating 4-Tab Bottom Pill Bar (Floating Functional Layer)
            FloatingNavBar(
                selectedTab = state.currentTab,
                onTabSelected = { viewModel.selectTab(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )

            // Ephemeral Banner / Toast Message
            state.bannerMessage?.let { banner ->
                LaunchedEffect(banner) {
                    delay(3000)
                    viewModel.clearBanner()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 86.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = banner,
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
