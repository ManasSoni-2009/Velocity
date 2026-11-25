package com.velocity.app

import com.velocity.app.domain.*
import com.velocity.app.platform.VelocityPlatformBridge
import kotlinx.coroutines.CoroutineScope
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
    val networkHealthState = MutableStateFlow(
        NetworkHealthSnapshot(
            wifi = LinkHealth(
                type = LinkType.WIFI,
                readiness = LinkReadiness.ONLINE,
                networkLabel = "Wi-Fi",
                detailText = "Internet ready",
            ),
            cellular = LinkHealth(
                type = LinkType.CELLULAR,
                readiness = LinkReadiness.ONLINE,
                networkLabel = "5G",
                detailText = "Internet ready",
                isMetered = true,
            ),
            summaryHeadline = "Wi-Fi & 5G ready",
        ),
    )

    override val telemetryFlow: StateFlow<CombinedTelemetry> = telemetryState
    override val connectionStateFlow: StateFlow<ConnectionStatus> = connectionState
    override val networkHealthFlow: StateFlow<NetworkHealthSnapshot> = networkHealthState

    var connectCalled = false
    var disconnectCalled = false
    var lastModeSet: BondingMode? = null
    var lastRulesUpdated: List<AppTunnelRule>? = null
    var lastSavedState: PersistedVelocityState? = null
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
            message = "Ready",
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

    override suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile> = ServerUriCodec.parse(uri)

    override suspend fun getInstalledApps(): List<AppTunnelRule> = listOf(
        AppTunnelRule("com.test.app1", "App One", TunnelRulePolicy.BONDED),
        AppTunnelRule("com.test.app2", "App Two", TunnelRulePolicy.BYPASS),
    )

    override suspend fun savePersistedState(state: PersistedVelocityState) {
        lastSavedState = state
    }

    override fun copyToClipboard(label: String, text: String) {
        copiedLabel = label
        copiedText = text
    }

    override fun isSystemDarkTheme(): Boolean = false
}

@OptIn(ExperimentalCoroutinesApi::class)
class BondingViewModelTest {

    private fun testServer() = ServerProfile(
        id = "srv-test",
        name = "Test Relay",
        host = "203.0.113.10",
        port = 51820,
        pskHex = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun testInitialStateAndThreeTabSwitching() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VelocityTab.BOND, state.currentTab)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionStatus.state)
        assertEquals(2, state.appRules.size)
        assertTrue(state.servers.isEmpty())

        viewModel.selectTab(VelocityTab.RELAYS)
        assertEquals(VelocityTab.RELAYS, viewModel.uiState.value.currentTab)

        viewModel.selectTab(VelocityTab.SETTINGS)
        assertEquals(VelocityTab.SETTINGS, viewModel.uiState.value.currentTab)
    }

    @Test
    fun testConnectPromptsRelaySetupWhenNoServerConfigured() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.toggleConnect()
        advanceUntilIdle()

        assertFalse(bridge.connectCalled)
        assertEquals(VelocityTab.RELAYS, viewModel.uiState.value.currentTab)
        assertTrue(viewModel.uiState.value.showImportDialog)
    }

    @Test
    fun testConnectBlockedWhenBothLinksHaveNoInternet() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        bridge.networkHealthState.value = NetworkHealthSnapshot(
            wifi = LinkHealth(LinkType.WIFI, LinkReadiness.NO_INTERNET, detailText = "Connected · No internet"),
            cellular = LinkHealth(LinkType.CELLULAR, LinkReadiness.UNAVAILABLE, detailText = "Mobile data off"),
            summaryHeadline = "No internet connection",
            advisoryMessage = "Wi-Fi has no internet access and mobile data is unavailable.",
        )

        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher), initialServers = listOf(testServer()))
        advanceUntilIdle()

        viewModel.toggleConnect()
        advanceUntilIdle()

        assertFalse(bridge.connectCalled)
        assertNotNull(viewModel.uiState.value.bannerMessage)
    }

    @Test
    fun testToggleConnectAndDisconnectWithServer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher), initialServers = listOf(testServer()))
        advanceUntilIdle()

        viewModel.toggleConnect()
        advanceUntilIdle()
        assertTrue(bridge.connectCalled)
        assertEquals(ConnectionState.BONDED, viewModel.uiState.value.connectionStatus.state)

        viewModel.toggleConnect()
        advanceUntilIdle()
        assertTrue(bridge.disconnectCalled)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.connectionStatus.state)
    }

    @Test
    fun testModeSwitchingAndPersistence() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher), initialServers = listOf(testServer()))
        advanceUntilIdle()

        viewModel.selectMode(BondingMode.LOW_LATENCY)
        advanceUntilIdle()
        assertEquals(BondingMode.LOW_LATENCY, bridge.lastModeSet)
        assertEquals(BondingMode.LOW_LATENCY, viewModel.uiState.value.connectionStatus.activeMode)
        assertEquals(BondingMode.LOW_LATENCY, bridge.lastSavedState?.activeMode)
    }

    @Test
    fun testManualServerAddAndUriImport() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        val added = viewModel.addManualServer("Home VPS", "198.51.100.2", "51820", "0123456789abcdef0123456789abcdef")
        advanceUntilIdle()
        assertTrue(added)
        assertEquals(1, viewModel.uiState.value.servers.size)

        val validUri = "velocity://0123456789abcdef0123456789abcdef@myrelay.net:51820?name=CustomRelay"
        assertTrue(viewModel.importServerUri(validUri))
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.servers.size)

        assertFalse(viewModel.importServerUri("invalid://something"))
        assertNotNull(viewModel.uiState.value.importError)
    }

    @Test
    fun testSpeedTestRunnerFlow() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher), initialServers = listOf(testServer()))
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
    fun testUpdateAppRuleAndSettings() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val viewModel = BondingViewModel(bridge, CoroutineScope(testDispatcher))
        advanceUntilIdle()

        viewModel.updateAppRule("com.test.app1", TunnelRulePolicy.LOW_LATENCY)
        advanceUntilIdle()
        val updated = viewModel.uiState.value.appRules.find { it.packageName == "com.test.app1" }
        assertEquals(TunnelRulePolicy.LOW_LATENCY, updated?.policy)
        assertNotNull(bridge.lastRulesUpdated)

        viewModel.updateSettings { it.copy(wifiBiasPercent = 85, reduceMotion = true) }
        advanceUntilIdle()
        assertEquals(85, viewModel.uiState.value.settings.wifiBiasPercent)
        assertTrue(viewModel.uiState.value.settings.reduceMotion)
    }
}
