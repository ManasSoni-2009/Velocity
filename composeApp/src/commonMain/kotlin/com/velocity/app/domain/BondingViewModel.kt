package com.velocity.app.domain

import com.velocity.app.platform.VelocityPlatformBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BondingUiState(
    val currentTab: VelocityTab = VelocityTab.BOND,
    val connectionStatus: ConnectionStatus = ConnectionStatus(),
    val telemetry: CombinedTelemetry = CombinedTelemetry(),
    val telemetryHistory: List<TelemetrySample> = emptyList(),
    val servers: List<ServerProfile> = defaultServers,
    val selectedServerId: String = defaultServers.first().id,
    val settings: VelocitySettings = VelocitySettings(),
    val appRules: List<AppTunnelRule> = emptyList(),
    val speedTest: SpeedTestProgress = SpeedTestProgress(),
    val isProbingServers: Boolean = false,
    val importError: String? = null,
    val showImportDialog: Boolean = false,
    val bannerMessage: String? = null,
) {
    val selectedServer: ServerProfile
        get() = servers.firstOrNull { it.id == selectedServerId } ?: servers.first()
}

val defaultServers = listOf(
    ServerProfile(
        id = "srv-us-east",
        name = "US East (Ashburn)",
        host = "useast.relay.velocity-multipath.net",
        port = 51820,
        pskHex = "a1b2c3d4e5f67890123456789abcdef0",
        region = "US East",
        wifiRttMs = 18,
        cellularRttMs = 38,
        isReachable = true,
    ),
    ServerProfile(
        id = "srv-eu-central",
        name = "EU Central (Frankfurt)",
        host = "eucentral.relay.velocity-multipath.net",
        port = 51820,
        pskHex = "b2c3d4e5f67890123456789abcdef01a",
        region = "EU Central",
        wifiRttMs = 28,
        cellularRttMs = 46,
        isReachable = true,
    ),
    ServerProfile(
        id = "srv-ap-southeast",
        name = "AP Southeast (Singapore)",
        host = "apsoutheast.relay.velocity-multipath.net",
        port = 51820,
        pskHex = "c3d4e5f67890123456789abcdef01a2b",
        region = "AP East",
        wifiRttMs = 42,
        cellularRttMs = 64,
        isReachable = true,
    ),
    ServerProfile(
        id = "srv-local-docker",
        name = "Self-Hosted Local Relay",
        host = "127.0.0.1",
        port = 51820,
        pskHex = "00112233445566778899aabbccddeeff",
        region = "Local",
        wifiRttMs = 2,
        cellularRttMs = 12,
        isReachable = true,
        isSelfHosted = true,
    ),
)

class BondingViewModel(
    private val bridge: VelocityPlatformBridge,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    private val _uiState = MutableStateFlow(BondingUiState())
    val uiState: StateFlow<BondingUiState> = _uiState.asStateFlow()

    private var speedTestJob: Job? = null
    private var telemetryJob: Job? = null
    private var connectionJob: Job? = null
    private val maxHistorySamples = 60

    init {
        observePlatform()
        loadInstalledApps()
        probeAllServers()
    }

    private fun observePlatform() {
        telemetryJob = scope.launch {
            bridge.telemetryFlow.collect { tel ->
                _uiState.update { current ->
                    val sample = TelemetrySample(
                        wifiDownloadMbps = tel.wifi.downloadMbps.toFloat(),
                        cellularDownloadMbps = tel.cellular.downloadMbps.toFloat(),
                        combinedDownloadMbps = tel.combinedDownloadMbps.toFloat(),
                        wifiUploadMbps = tel.wifi.uploadMbps.toFloat(),
                        cellularUploadMbps = tel.cellular.uploadMbps.toFloat(),
                        combinedUploadMbps = tel.combinedUploadMbps.toFloat(),
                        wifiRttMs = tel.wifi.rttMs.toFloat(),
                        cellularRttMs = tel.cellular.rttMs.toFloat(),
                        effectiveRttMs = tel.effectiveRttMs.toFloat(),
                    )
                    val history = (current.telemetryHistory + sample)
                        .takeLast(maxHistorySamples)

                    current.copy(
                        telemetry = tel,
                        telemetryHistory = history,
                    )
                }
            }
        }

        connectionJob = scope.launch {
            bridge.connectionStateFlow.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
    }

    fun selectTab(tab: VelocityTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun toggleConnect() {
        val state = _uiState.value
        val isConnected = state.connectionStatus.state.isActiveOrTransitioning

        scope.launch {
            if (isConnected) {
                bridge.disconnect()
            } else {
                val server = state.selectedServer
                bridge.connect(
                    server = server,
                    mode = state.connectionStatus.activeMode,
                    settings = state.settings,
                    appRules = state.appRules,
                )
            }
        }
    }

    fun selectMode(mode: BondingMode) {
        scope.launch {
            bridge.setMode(mode)
            _uiState.update { current ->
                current.copy(
                    connectionStatus = current.connectionStatus.copy(activeMode = mode),
                )
            }
        }
    }

    fun selectServer(server: ServerProfile) {
        _uiState.update { it.copy(selectedServerId = server.id) }
        val status = _uiState.value.connectionStatus
        if (status.state.isActiveOrTransitioning) {
            // Reconnect to newly selected server
            scope.launch {
                bridge.disconnect()
                bridge.connect(
                    server = server,
                    mode = status.activeMode,
                    settings = _uiState.value.settings,
                    appRules = _uiState.value.appRules,
                )
            }
        }
    }

    fun openImportDialog() {
        _uiState.update { it.copy(showImportDialog = true, importError = null) }
    }

    fun closeImportDialog() {
        _uiState.update { it.copy(showImportDialog = false, importError = null) }
    }

    fun importServerUri(rawUri: String): Boolean {
        val parsed = ServerUriCodec.parse(rawUri)
        return parsed.fold(
            onSuccess = { profile ->
                _uiState.update { current ->
                    val filtered = current.servers.filterNot { it.id == profile.id }
                    current.copy(
                        servers = filtered + profile,
                        selectedServerId = profile.id,
                        showImportDialog = false,
                        importError = null,
                        bannerMessage = "Imported server: ${profile.name}",
                    )
                }
                probeAllServers()
                true
            },
            onFailure = { err ->
                _uiState.update {
                    it.copy(importError = err.message ?: "Failed to parse URI")
                }
                false
            },
        )
    }

    fun startSpeedTest() {
        if (_uiState.value.speedTest.isRunning) return
        speedTestJob?.cancel()
        speedTestJob = scope.launch {
            val server = _uiState.value.selectedServer
            _uiState.update {
                it.copy(
                    speedTest = SpeedTestProgress(
                        isRunning = true,
                        phase = "Initializing Dual-Path Test...",
                        progressFraction = 0.05f,
                    ),
                )
            }

            val result = bridge.runSpeedTest(server) { phase, fraction, liveMbps ->
                _uiState.update { current ->
                    current.copy(
                        speedTest = current.speedTest.copy(
                            isRunning = true,
                            phase = phase,
                            progressFraction = fraction,
                            liveCombinedMbps = liveMbps,
                        ),
                    )
                }
            }

            result.fold(
                onSuccess = { res ->
                    _uiState.update { current ->
                        current.copy(
                            speedTest = SpeedTestProgress(
                                isRunning = false,
                                phase = "Complete",
                                progressFraction = 1f,
                                liveCombinedMbps = res.combinedDownloadMbps,
                                result = res,
                            ),
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update { current ->
                        current.copy(
                            speedTest = SpeedTestProgress(
                                isRunning = false,
                                phase = "Speed Test Failed",
                                progressFraction = 0f,
                                errorMessage = err.message ?: "Unknown error",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun updateAppRule(packageName: String, newPolicy: TunnelRulePolicy) {
        val updated = _uiState.value.appRules.map {
            if (it.packageName == packageName) it.copy(policy = newPolicy) else it
        }
        _uiState.update { it.copy(appRules = updated) }
        scope.launch {
            bridge.updateSplitTunnelApps(updated)
        }
    }

    fun updateSettings(transform: (VelocitySettings) -> VelocitySettings) {
        val newSettings = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = newSettings) }
    }

    fun clearBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun copyToClipboard(label: String, text: String) {
        bridge.copyToClipboard(label, text)
        _uiState.update { it.copy(bannerMessage = "Copied $label to clipboard") }
    }

    fun probeAllServers() {
        if (_uiState.value.isProbingServers) return
        scope.launch {
            _uiState.update { it.copy(isProbingServers = true) }
            val results = bridge.probeServers(_uiState.value.servers)
            _uiState.update { current ->
                val resultMap = results.associateBy { it.serverId }
                val updatedServers = current.servers.map { srv ->
                    val res = resultMap[srv.id]
                    if (res != null) {
                        srv.copy(
                            wifiRttMs = res.wifiRttMs,
                            cellularRttMs = res.cellularRttMs,
                            isReachable = res.reachable,
                        )
                    } else {
                        srv
                    }
                }
                current.copy(
                    servers = updatedServers,
                    isProbingServers = false,
                )
            }
        }
    }

    private fun loadInstalledApps() {
        scope.launch {
            val apps = bridge.getInstalledApps()
            if (apps.isNotEmpty()) {
                _uiState.update { it.copy(appRules = apps) }
            }
        }
    }
}
