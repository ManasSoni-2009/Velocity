package com.velocity.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.BondingViewModel
import com.velocity.app.domain.VelocityTab
import com.velocity.app.platform.VelocityPlatformBridge
import com.velocity.app.ui.components.PantoneBottomBar
import com.velocity.app.ui.screens.BondScreen
import com.velocity.app.ui.screens.RelaysScreen
import com.velocity.app.ui.screens.SettingsScreen
import com.velocity.app.ui.theme.LocalVelocityA11y
import com.velocity.app.ui.theme.LocalVelocitySemantic
import com.velocity.app.ui.theme.VelocityColors
import com.velocity.app.ui.theme.VelocityTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VelocityApp(
    bridge: VelocityPlatformBridge,
    viewModel: BondingViewModel = remember(bridge) { BondingViewModel(bridge) },
) {
    val state by viewModel.uiState.collectAsState()
    val systemReduceMotion = remember(bridge) { bridge.isReduceMotionEnabled() }

    VelocityTheme(
        themeMode = state.settings.themeMode,
        reduceTransparency = state.settings.reduceTransparency,
        reduceMotion = state.settings.reduceMotion || systemReduceMotion,
        highContrast = state.settings.highContrast,
    ) {
        val semantic = LocalVelocitySemantic.current
        val a11y = LocalVelocityA11y.current
        val tabs = VelocityTab.entries
        val pagerState = rememberPagerState(
            initialPage = state.currentTab.ordinal.coerceIn(0, tabs.lastIndex),
            pageCount = { tabs.size },
        )
        val backdropLayer = rememberGraphicsLayer()

        // Animate smoothly whenever ViewModel tab changes (navbar tap or CTA jump)
        LaunchedEffect(state.currentTab, a11y.reduceMotion) {
            val target = state.currentTab.ordinal
            if (pagerState.currentPage != target) {
                if (a11y.reduceMotion) {
                    pagerState.scrollToPage(target)
                } else {
                    pagerState.animateScrollToPage(
                        page = target,
                        animationSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                }
            }
        }

        // Sync swipe gestures back to ViewModel when pager settles
        LaunchedEffect(pagerState.settledPage) {
            val settledTab = tabs.getOrNull(pagerState.settledPage)
            if (settledTab != null && settledTab != state.currentTab) {
                viewModel.selectTab(settledTab)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(semantic.canvas),
        ) {
            // Capture the live page tree into backdropLayer ONCE per draw so the floating
            // Liquid Glass navbar blurs and refracts content directly behind it at 60/120fps.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(semantic.canvas)
                    .drawWithContent {
                        backdropLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(backdropLayer)
                    },
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) { pageIndex ->
                    when (tabs[pageIndex]) {
                        VelocityTab.BOND -> BondScreen(
                            state = state,
                            onToggleConnect = { viewModel.toggleConnect() },
                            onSelectMode = { viewModel.selectMode(it) },
                            onRefreshHealth = { viewModel.refreshNetworkHealth() },
                            onOpenRelays = { viewModel.selectTab(VelocityTab.RELAYS) },
                        )
                        VelocityTab.RELAYS -> RelaysScreen(
                            state = state,
                            onSelectServer = { viewModel.selectServer(it) },
                            onDeleteServer = { viewModel.deleteServer(it) },
                            onOpenImportDialog = { viewModel.openImportDialog() },
                            onCloseImportDialog = { viewModel.closeImportDialog() },
                            onImportUri = { viewModel.importServerUri(it) },
                            onAddManualServer = { name, host, port, psk ->
                                viewModel.addManualServer(name, host, port, psk)
                            },
                            onOpenSelfHostSheet = { viewModel.openSelfHostSheet() },
                            onCloseSelfHostSheet = { viewModel.closeSelfHostSheet() },
                            onProbeServers = { viewModel.probeAllServers() },
                            onCopySnippet = { label, text -> viewModel.copyToClipboard(label, text) },
                        )
                        VelocityTab.SETTINGS -> SettingsScreen(
                            state = state,
                            onUpdateRule = { pkg, policy -> viewModel.updateAppRule(pkg, policy) },
                            onUpdateSettings = { transform -> viewModel.updateSettings(transform) },
                            onStartSpeedTest = { viewModel.startSpeedTest() },
                        )
                    }
                }
            }

            // Ephemeral toast floating cleanly above the dock
            AnimatedVisibility(
                visible = state.bannerMessage != null,
                enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(140)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 92.dp),
            ) {
                state.bannerMessage?.let { banner ->
                    LaunchedEffect(banner) {
                        delay(2800)
                        viewModel.clearBanner()
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(semantic.primaryInk)
                            .border(1.dp, VelocityColors.LuminousLavender.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodyMedium,
                            color = semantic.canvas,
                        )
                    }
                }
            }

            // Floating Liquid Glass navbar with live optical refraction (#container-glass)
            PantoneBottomBar(
                selectedTab = state.currentTab,
                pageProgress = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                backdropLayer = backdropLayer,
                onTabSelected = { viewModel.selectTab(it) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
