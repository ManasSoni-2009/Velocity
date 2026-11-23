package com.velocity.app

import com.velocity.app.domain.*
import com.velocity.app.platform.VelocityPlatformBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FakePlatformBridge : VelocityPlatformBridge {
    val telemetryState = MutableStateFlow(CombinedTelemetry())
    val connectionState = MutableStateFlow(ConnectionStatus())

    override val telemetryFlow: StateFlow<CombinedTelemetry> = telemetryState
    override val connectionStateFlow: StateFlow<ConnectionStatus> = connectionState

    var connectCalled = false
    var disconnectCalled = false
    var lastModeSet: BondingMode? = null
    var lastRulesUpdated: List<AppTunnelRule>? = null
    var copiedLabel: String? = null
    var copiedText: String? = null

    override suspend fun connect(
        server: ServerProfile,
        mode: BondingMode,
        settings: VelocitySettings,
        appRules: List<AppTunnelRule>,
    ): Result<Unit> {
        connectCalled = true
        connectionState.value = ConnectionStatus(
            state = ConnectionState.BONDED,
            activeMode = mode,
            activeServerId = server.id,
            message = "Bonded",
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        disconnectCalled = true
        connectionState.value = ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Disconnected",
        )
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: BondingMode): Result<Unit> {
        lastModeSet = mode
        connectionState.value = connectionState.value.copy(activeMode = mode)
        return Result.success(Unit)
    }

    override suspend fun updateSplitTunnelApps(rules: List<AppTunnelRule>): Result<Unit> {
        lastRulesUpdated = rules
        return Result.success(Unit)
    }

    override suspend fun runSpeedTest(
        server: ServerProfile,
        onProgress: (phase: String, fraction: Float, liveMbps: Double) -> Unit,
    ): Result<SpeedTestResult> {
        onProgress("Testing Wi-Fi", 0.5f, 40.0)
        return Result.success(
            SpeedTestResult(
                wifiDownloadMbps = 40.0,
                wifiUploadMbps = 10.0,
                cellularDownloadMbps = 30.0,
                cellularUploadMbps = 5.0,
                combinedDownloadMbps = 65.0,
                combinedUploadMbps = 14.0,
                wifiRttMs = 20.0,
                cellularRttMs = 35.0,
                combinedRttMs = 18.0,
                gainPercent = 62,
                durationMs = 1500L,
            ),
        )
    }

    override suspend fun probeServers(servers: List<ServerProfile>): List<ServerProbeResult> {
        return servers.map {
            ServerProbeResult(it.id, wifiRttMs = 15, cellularRttMs = 30, reachable = true)
        }
    }

    override suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile> {
        return ServerUriCodec.parse(uri)
    }

    override suspend fun getInstalledApps(): List<AppTunnelRule> {
        return listOf(
            AppTunnelRule("com.test.app1", "App One", TunnelRulePolicy.BONDED),
            AppTunnelRule("com.test.app2", "App Two", TunnelRulePolicy.BYPASS),
        )
    }

    override fun copyToClipboard(label: String, text: String) {
        copiedLabel = label
        copiedText = text
    }

    override fun isSystemDarkTheme(): Boolean = true
}

@OptIn(ExperimentalCoroutinesApi::class)
class BondingViewModelTest {

    @Test
    fun testInitialStateAndTabSwitching() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VelocityTab.BOND, state.currentTab)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionStatus.state)
        assertEquals(2, state.appRules.size)
        assertTrue(state.servers.isNotEmpty())

        viewModel.selectTab(VelocityTab.TELEMETRY)
        assertEquals(VelocityTab.TELEMETRY, viewModel.uiState.value.currentTab)

        viewModel.selectTab(VelocityTab.SERVERS)
        assertEquals(VelocityTab.SERVERS, viewModel.uiState.value.currentTab)

        viewModel.selectTab(VelocityTab.SETTINGS)
        assertEquals(VelocityTab.SETTINGS, viewModel.uiState.value.currentTab)
    }

    @Test
    fun testToggleConnectAndDisconnect() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        // Connect
        viewModel.toggleConnect()
        advanceUntilIdle()
        assertTrue(bridge.connectCalled)
        assertEquals(ConnectionState.BONDED, viewModel.uiState.value.connectionStatus.state)

        // Disconnect
        viewModel.toggleConnect()
        advanceUntilIdle()
        assertTrue(bridge.disconnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.connectionStatus.state)
    }

    @Test
    fun testModeSwitching() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.selectMode(BondingMode.LOW_LATENCY)
        advanceUntilIdle()
        assertEquals(BondingMode.LOW_LATENCY, bridge.lastModeSet)
        assertEquals(BondingMode.LOW_LATENCY, viewModel.uiState.value.connectionStatus.activeMode)

        viewModel.selectMode(BondingMode.FAILOVER)
        advanceUntilIdle()
        assertEquals(BondingMode.FAILOVER, bridge.lastModeSet)
        assertEquals(BondingMode.FAILOVER, viewModel.uiState.value.connectionStatus.activeMode)
    }

    @Test
    fun testServerImportSuccessAndFailure() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        val validUri = "velocity://0123456789abcdef0123456789abcdef@myrelay.net:51820?name=CustomRelay"
        val success = viewModel.importServerUri(validUri)
        advanceUntilIdle()
        assertTrue(success)
        assertNull(viewModel.uiState.value.importError)
        val imported = viewModel.uiState.value.servers.find { it.name == "CustomRelay" }
        assertNotNull(imported)
        assertEquals(imported.id, viewModel.uiState.value.selectedServerId)

        val invalidUri = "invalid://something"
        val failed = viewModel.importServerUri(invalidUri)
        advanceUntilIdle()
        assertFalse(failed)
        assertNotNull(viewModel.uiState.value.importError)
    }

    @Test
    fun testSpeedTestRunnerFlow() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.startSpeedTest()
        advanceUntilIdle()

        val speedTest = viewModel.uiState.value.speedTest
        assertFalse(speedTest.isRunning)
        assertNotNull(speedTest.result)
        assertEquals(65.0, speedTest.result?.combinedDownloadMbps)
        assertEquals(62, speedTest.result?.gainPercent)
    }

    @Test
    fun testUpdateAppRule() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.updateAppRule("com.test.app1", TunnelRulePolicy.LOW_LATENCY)
        advanceUntilIdle()

        val updated = viewModel.uiState.value.appRules.find { it.packageName == "com.test.app1" }
        assertEquals(TunnelRulePolicy.LOW_LATENCY, updated?.policy)
        assertNotNull(bridge.lastRulesUpdated)
    }

    @Test
    fun testUpdateSettings() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.updateSettings { it.copy(wifiBiasPercent = 85, reduceTransparency = true) }
        val settings = viewModel.uiState.value.settings
        assertEquals(85, settings.wifiBiasPercent)
        assertTrue(settings.reduceTransparency)
    }
}
