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
    val networkHealth: NetworkHealthSnapshot = NetworkHealthSnapshot(),
    val telemetry: CombinedTelemetry = CombinedTelemetry(),
    val telemetryHistory: List<TelemetrySample> = emptyList(),
    val servers: List<ServerProfile> = emptyList(),
    val selectedServerId: String? = null,
    val settings: VelocitySettings = VelocitySettings(),
    val appRules: List<AppTunnelRule> = emptyList(),
    val speedTest: SpeedTestProgress = SpeedTestProgress(),
    val isProbingServers: Boolean = false,
    val importError: String? = null,
    val showImportDialog: Boolean = false,
    val showSelfHostSheet: Boolean = false,
    val bannerMessage: String? = null,
) {
    val selectedServer: ServerProfile?
        get() = servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull()
}

val defaultServers: List<ServerProfile> = emptyList()

class BondingViewModel(
    private val bridge: VelocityPlatformBridge,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    initialServers: List<ServerProfile> = emptyList(),
) {
    private val _uiState = MutableStateFlow(
        BondingUiState(
            servers = initialServers,
            selectedServerId = initialServers.firstOrNull()?.id,
        ),
    )
    val uiState: StateFlow<BondingUiState> = _uiState.asStateFlow()

    private var speedTestJob: Job? = null
    private var telemetryJob: Job? = null
    private var connectionJob: Job? = null
    private var networkHealthJob: Job? = null
    private val maxHistorySamples = 45

    init {
        observePlatform()
        bootstrapInitialData()
    }

    private fun bootstrapInitialData() {
        scope.launch {
            val persisted = bridge.loadPersistedState()
            if (persisted != null) {
                _uiState.update { current ->
                    val mergedServers = if (persisted.servers.isNotEmpty()) persisted.servers else current.servers
                    val selectedId = persisted.selectedServerId
                        ?.takeIf { id -> mergedServers.any { it.id == id } }
                        ?: mergedServers.firstOrNull()?.id
                    current.copy(
                        servers = mergedServers,
                        selectedServerId = selectedId,
                        settings = persisted.settings,
                        connectionStatus = current.connectionStatus.copy(activeMode = persisted.activeMode),
                    )
                }
            }

            val apps = bridge.getInstalledApps()
            if (apps.isNotEmpty()) {
                val savedPolicies = persisted?.appRulePolicies ?: emptyMap()
                val mergedApps = apps.map { app ->
                    val saved = savedPolicies[app.packageName]
                    if (saved != null) app.copy(policy = saved) else app
                }
                _uiState.update { it.copy(appRules = mergedApps) }
            }

            bridge.refreshNetworkHealth()
            if (_uiState.value.servers.isNotEmpty()) {
                probeAllServers()
            }
        }
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

                    val liveQuota = tel.relayQuota
                    val activeId = current.selectedServerId
                    val updatedServers = if (liveQuota != null && activeId != null) {
                        current.servers.map { srv ->
                            if (srv.id == activeId && srv.quota != liveQuota) srv.copy(quota = liveQuota) else srv
                        }
                    } else {
                        current.servers
                    }

                    current.copy(
                        telemetry = tel,
                        telemetryHistory = history,
                        servers = updatedServers,
                    )
                }
            }
        }

        connectionJob = scope.launch {
            bridge.connectionStateFlow.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        networkHealthJob = scope.launch {
            bridge.networkHealthFlow.collect { health ->
                _uiState.update { it.copy(networkHealth = health) }
            }
        }
    }

    fun selectTab(tab: VelocityTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun refreshNetworkHealth() {
        scope.launch {
            bridge.refreshNetworkHealth()
        }
    }

    fun toggleConnect() {
        val state = _uiState.value
        val isActive = state.connectionStatus.state.isActiveOrTransitioning

        scope.launch {
            if (isActive) {
                bridge.disconnect()
                return@launch
            }

            val server = state.selectedServer
            if (server == null) {
                _uiState.update {
                    it.copy(
                        currentTab = VelocityTab.RELAYS,
                        showImportDialog = true,
                        bannerMessage = "Add a relay server to start bonding",
                    )
                }
                return@launch
            }

            bridge.refreshNetworkHealth()
            val latestHealth = bridge.networkHealthFlow.value
            if (!latestHealth.hasAnyInternet) {
                _uiState.update {
                    it.copy(
                        bannerMessage = latestHealth.advisoryMessage
                            ?: "No internet connection available on Wi-Fi or Cellular",
                    )
                }
                return@launch
            }

            val result = bridge.connect(
                server = server,
                mode = state.connectionStatus.activeMode,
                settings = state.settings,
                appRules = state.appRules,
            )
            result.onFailure { err ->
                _uiState.update {
                    it.copy(bannerMessage = err.message ?: "Unable to start tunnel")
                }
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
            persistCurrentState()
        }
    }

    fun selectServer(server: ServerProfile) {
        _uiState.update { it.copy(selectedServerId = server.id) }
        persistCurrentStateAsync()

        val status = _uiState.value.connectionStatus
        if (status.state.isActiveOrTransitioning) {
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

    fun deleteServer(serverId: String) {
        val current = _uiState.value
        val remaining = current.servers.filterNot { it.id == serverId }
        val nextSelected = if (current.selectedServerId == serverId) {
            remaining.firstOrNull()?.id
        } else {
            current.selectedServerId
        }
        _uiState.update {
            it.copy(
                servers = remaining,
                selectedServerId = nextSelected,
                bannerMessage = "Relay removed",
            )
        }
        persistCurrentStateAsync()
        if (current.connectionStatus.state.isActiveOrTransitioning && current.selectedServerId == serverId) {
            scope.launch { bridge.disconnect() }
        }
    }

    fun openImportDialog() {
        _uiState.update { it.copy(showImportDialog = true, importError = null) }
    }

    fun closeImportDialog() {
        _uiState.update { it.copy(showImportDialog = false, importError = null) }
    }

    fun openSelfHostSheet() {
        _uiState.update { it.copy(showSelfHostSheet = true) }
    }

    fun closeSelfHostSheet() {
        _uiState.update { it.copy(showSelfHostSheet = false) }
    }

    fun importServerUri(rawUri: String): Boolean {
        val parsed = ServerUriCodec.parse(rawUri)
        return parsed.fold(
            onSuccess = { profile ->
                saveOrReplaceServer(profile, "Added relay: ${profile.name}")
                true
            },
            onFailure = { err ->
                _uiState.update {
                    it.copy(importError = err.message ?: "Invalid velocity:// URI")
                }
                false
            },
        )
    }

    fun addManualServer(
        name: String,
        host: String,
        portText: String,
        psk: String,
        region: String = "Self-Hosted",
    ): Boolean {
        val cleanHost = host.trim()
        val cleanPsk = psk.trim()
        val port = portText.trim().toIntOrNull()
        val error = when {
            cleanHost.isEmpty() -> "Host or IP address is required"
            port == null || port !in 1..65535 -> "Port must be between 1 and 65535"
            cleanPsk.length < 16 -> "Key must be at least 16 characters"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(importError = error) }
            return false
        }

        val cleanName = name.trim().ifEmpty { cleanHost }
        val profile = ServerProfile(
            id = "srv-${cleanHost.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-$port",
            name = cleanName,
            host = cleanHost,
            port = port!!,
            pskHex = cleanPsk,
            region = region.trim().ifEmpty { "Self-Hosted" },
            mtu = _uiState.value.settings.mtu,
            isSelfHosted = true,
        )
        saveOrReplaceServer(profile, "Added relay: ${profile.name}")
        return true
    }

    private fun saveOrReplaceServer(profile: ServerProfile, message: String) {
        _uiState.update { current ->
            val filtered = current.servers.filterNot { it.id == profile.id }
            current.copy(
                servers = filtered + profile,
                selectedServerId = profile.id,
                showImportDialog = false,
                importError = null,
                bannerMessage = message,
            )
        }
        persistCurrentStateAsync()
        probeAllServers()
    }

    fun startSpeedTest() {
        if (_uiState.value.speedTest.isRunning) return
        val server = _uiState.value.selectedServer
        if (server == null) {
            _uiState.update {
                it.copy(
                    speedTest = SpeedTestProgress(
                        isRunning = false,
                        phase = "Unavailable",
                        errorMessage = "Add and select a relay server first",
                    ),
                )
            }
            return
        }

        speedTestJob?.cancel()
        speedTestJob = scope.launch {
            _uiState.update {
                it.copy(
                    speedTest = SpeedTestProgress(
                        isRunning = true,
                        phase = "Testing your links...",
                        progressFraction = 0.08f,
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
                                phase = "Benchmark failed",
                                progressFraction = 0f,
                                errorMessage = err.message ?: "Relay did not respond",
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
        persistCurrentStateAsync()
        scope.launch {
            bridge.updateSplitTunnelApps(updated)
        }
    }

    fun updateSettings(transform: (VelocitySettings) -> VelocitySettings) {
        val newSettings = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = newSettings) }
        persistCurrentStateAsync()
        scope.launch {
            bridge.updateTuning(newSettings)
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun copyToClipboard(label: String, text: String) {
        bridge.copyToClipboard(label, text)
        _uiState.update { it.copy(bannerMessage = "Copied $label") }
    }

    fun probeAllServers() {
        val serversToProbe = _uiState.value.servers
        if (serversToProbe.isEmpty() || _uiState.value.isProbingServers) return
        scope.launch {
            _uiState.update { it.copy(isProbingServers = true) }
            val results = bridge.probeServers(serversToProbe)
            _uiState.update { current ->
                val resultMap = results.associateBy { it.serverId }
                val updatedServers = current.servers.map { srv ->
                    val res = resultMap[srv.id]
                    if (res != null) {
                        srv.copy(
                            wifiRttMs = res.wifiRttMs,
                            cellularRttMs = res.cellularRttMs,
                            isReachable = res.reachable,
                            lastProbeError = res.error,
                            quota = res.quota ?: srv.quota,
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
            persistCurrentState()
        }
    }

    private fun persistCurrentStateAsync() {
        scope.launch { persistCurrentState() }
    }

    private suspend fun persistCurrentState() {
        val current = _uiState.value
        val persisted = PersistedVelocityState(
            servers = current.servers,
            selectedServerId = current.selectedServerId,
            settings = current.settings,
            appRulePolicies = current.appRules.associate { it.packageName to it.policy },
            activeMode = current.connectionStatus.activeMode,
        )
        bridge.savePersistedState(persisted)
    }
}
