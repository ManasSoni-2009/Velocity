package com.velocity.app.platform

import com.velocity.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.InetAddress
import kotlin.math.sin
import kotlin.random.Random

/**
 * JVM Desktop implementation of [VelocityPlatformBridge].
 * Provides an interactive preview mode for UI verification & desktop inspection,
 * alongside real DNS/host validation when probing configured relays.
 */
class DesktopPlatformBridge(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val persistToDisk: Boolean = false,
) : VelocityPlatformBridge {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val _telemetryFlow = MutableStateFlow(CombinedTelemetry())
    override val telemetryFlow: StateFlow<CombinedTelemetry> = _telemetryFlow.asStateFlow()

    private val _connectionStateFlow = MutableStateFlow(
        ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Tap to bond available networks",
            activeMode = BondingMode.SPEED,
        ),
    )
    override val connectionStateFlow: StateFlow<ConnectionStatus> = _connectionStateFlow.asStateFlow()

    private val _networkHealthFlow = MutableStateFlow(
        NetworkHealthSnapshot(
            wifi = LinkHealth(
                type = LinkType.WIFI,
                readiness = LinkReadiness.ONLINE,
                networkLabel = "Wi-Fi",
                detailText = "Internet ready",
                isMetered = false,
                signalBars = 4,
                signalDbm = -54,
                linkSpeedMbps = 433,
            ),
            cellular = LinkHealth(
                type = LinkType.CELLULAR,
                readiness = LinkReadiness.ONLINE,
                networkLabel = "5G",
                detailText = "Internet ready",
                isMetered = true,
                signalBars = 3,
                signalDbm = -78,
            ),
            summaryHeadline = "Wi-Fi & 5G ready",
            advisoryMessage = null,
            lastUpdatedMs = System.currentTimeMillis(),
        ),
    )
    override val networkHealthFlow: StateFlow<NetworkHealthSnapshot> = _networkHealthFlow.asStateFlow()

    private var telemetrySimulationJob: Job? = null
    private var simulatedBytesRx = 0L
    private var simulatedBytesTx = 0L
    private var currentMode = BondingMode.SPEED

    init {
        startTelemetrySimulation()
    }

    fun setSimulatedNetworkHealth(snapshot: NetworkHealthSnapshot) {
        _networkHealthFlow.value = snapshot
    }

    private fun startTelemetrySimulation() {
        telemetrySimulationJob?.cancel()
        telemetrySimulationJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(150)
                tick++
                val isBonded = _connectionStateFlow.value.state == ConnectionState.BONDED
                val health = _networkHealthFlow.value
                val wifiOk = health.wifi.readiness.hasInternet
                val cellOk = health.cellular.readiness.hasInternet

                val (wifiMbps, cellMbps, effRtt) = if (isBonded) {
                    val osc = sin(tick * 0.14) * 5.0
                    val w = if (wifiOk) (48.0 + osc + Random.nextDouble(-1.5, 1.5)).coerceAtLeast(1.0) else 0.0
                    val c = if (cellOk && currentMode != BondingMode.FAILOVER) {
                        (31.0 - osc * 0.4 + Random.nextDouble(-1.5, 1.5)).coerceAtLeast(1.0)
                    } else {
                        0.0
                    }

                    when (currentMode) {
                        BondingMode.SPEED -> Triple(w, c, 21.0)
                        BondingMode.LOW_LATENCY -> Triple(w * 0.85, c * 0.85, 14.0)
                        BondingMode.FAILOVER -> Triple(if (wifiOk) w else 0.0, if (!wifiOk && cellOk) c else 0.0, 18.0)
                    }
                } else {
                    Triple(0.0, 0.0, 0.0)
                }

                val combinedMbps = wifiMbps + cellMbps
                if (isBonded) {
                    simulatedBytesRx += (combinedMbps * 1024 * 1024 / 8 / 6.6).toLong()
                    simulatedBytesTx += (combinedMbps * 1024 * 1024 / 8 / 24.0).toLong()
                }

                val wifiWeight = if (combinedMbps > 0.1) ((wifiMbps / combinedMbps) * 100).toInt() else 0

                _telemetryFlow.value = CombinedTelemetry(
                    timestampMs = System.currentTimeMillis(),
                    wifi = LinkTelemetry(
                        pathId = 0,
                        type = LinkType.WIFI,
                        interfaceName = "wlan0",
                        active = isBonded && wifiOk && wifiMbps > 0.0,
                        hasInternet = wifiOk,
                        downloadMbps = wifiMbps,
                        uploadMbps = wifiMbps * 0.25,
                        rttMs = if (isBonded && wifiOk) 16.0 else 0.0,
                        jitterMs = if (isBonded && wifiOk) 2.1 else 0.0,
                        lossPercent = 0.1,
                        schedulerWeightPercent = wifiWeight,
                        signalDbm = health.wifi.signalDbm,
                        linkSpeedMbps = health.wifi.linkSpeedMbps,
                    ),
                    cellular = LinkTelemetry(
                        pathId = 1,
                        type = LinkType.CELLULAR,
                        interfaceName = "rmnet0",
                        active = isBonded && cellOk && cellMbps > 0.0,
                        hasInternet = cellOk,
                        isMetered = true,
                        downloadMbps = cellMbps,
                        uploadMbps = cellMbps * 0.20,
                        rttMs = if (isBonded && cellOk) 34.0 else 0.0,
                        jitterMs = if (isBonded && cellOk) 4.0 else 0.0,
                        lossPercent = 0.2,
                        schedulerWeightPercent = if (combinedMbps > 0.1) 100 - wifiWeight else 0,
                        signalDbm = health.cellular.signalDbm,
                        linkSpeedMbps = 150,
                    ),
                    combinedDownloadMbps = combinedMbps,
                    combinedUploadMbps = combinedMbps * 0.22,
                    effectiveRttMs = effRtt,
                    aggregationEfficiencyPercent = if (isBonded && wifiMbps > 0 && cellMbps > 0) 92 else 100,
                    reorderBufferDepth = if (isBonded) 3 else 0,
                    reorderTimeoutMs = 22,
                    fecRecoveredPackets = if (isBonded) (tick / 6).toLong() else 0L,
                    fecSentPackets = if (isBonded) (tick / 3).toLong() else 0L,
                    duplicatedPackets = 0L,
                    sessionBytesRx = simulatedBytesRx,
                    sessionBytesTx = simulatedBytesTx,
                    cellularBytesUsedMb = (simulatedBytesRx * 0.36) / (1024 * 1024),
                    activeMode = currentMode.wireValue,
                    connectionState = _connectionStateFlow.value.state.code,
                    statusMessage = _connectionStateFlow.value.message,
                    relayQuota = if (isBonded) {
                        RelayQuotaInfo(
                            provider = "Oracle Cloud Free",
                            period = "2026-11",
                            usedBytes = 1_319_413_953_331L + simulatedBytesRx,
                            limitBytes = 10_995_116_277_760L,
                            remainingBytes = (10_995_116_277_760L - 1_319_413_953_331L - simulatedBytesRx).coerceAtLeast(0L),
                            percentUsed = 12.0,
                            activeSessions = 14,
                            resetAtEpochMs = 1_796_083_200_000L,
                        )
                    } else {
                        null
                    },
                )
            }
        }
    }

    override suspend fun connect(
        server: ServerProfile,
        mode: BondingMode,
        settings: VelocitySettings,
        appRules: List<AppTunnelRule>,
    ): Result<Unit> {
        currentMode = mode
        _connectionStateFlow.value = ConnectionStatus(
            state = ConnectionState.CONNECTING,
            message = "Verifying relay...",
            activeMode = mode,
            activeServerId = server.id,
        )

        delay(220)

        val health = _networkHealthFlow.value
        val msg = when {
            health.wifi.readiness.hasInternet && health.cellular.readiness.hasInternet ->
                "Bonded · Wi-Fi + ${health.cellular.networkLabel}"
            health.cellular.readiness.hasInternet ->
                "Connected · ${health.cellular.networkLabel} only"
            health.wifi.readiness.hasInternet ->
                "Connected · Wi-Fi only"
            else -> {
                _connectionStateFlow.value = ConnectionStatus(
                    state = ConnectionState.ERROR,
                    message = "No internet path",
                    activeMode = mode,
                )
                return Result.failure(IllegalStateException("No internet path"))
            }
        }

        _connectionStateFlow.value = ConnectionStatus(
            state = ConnectionState.BONDED,
            message = msg,
            activeMode = mode,
            connectedSinceEpochMs = System.currentTimeMillis(),
            activeServerId = server.id,
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        _connectionStateFlow.value = ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Tap to bond available networks",
            activeMode = currentMode,
        )
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: BondingMode): Result<Unit> {
        currentMode = mode
        val current = _connectionStateFlow.value
        _connectionStateFlow.value = current.copy(activeMode = mode)
        return Result.success(Unit)
    }

    override suspend fun updateSplitTunnelApps(rules: List<AppTunnelRule>): Result<Unit> = Result.success(Unit)

    override suspend fun runSpeedTest(
        server: ServerProfile,
        onProgress: (phase: String, fraction: Float, liveMbps: Double) -> Unit,
    ): Result<SpeedTestResult> = withContext(Dispatchers.Default) {
        for (i in 1..6) {
            delay(50)
            onProgress("Testing Wi-Fi…", i * 0.05f, 46.0 + i)
        }
        for (i in 1..6) {
            delay(50)
            onProgress("Testing Cellular…", 0.3f + i * 0.05f, 32.0 + i)
        }
        for (i in 1..8) {
            delay(50)
            onProgress("Testing combined bond…", 0.6f + i * 0.05f, 75.0 + i)
        }
        val wifiDl = 48.2
        val cellDl = 33.4
        val combinedDl = 78.9
        onProgress("Complete", 1.0f, combinedDl)

        Result.success(
            SpeedTestResult(
                wifiDownloadMbps = wifiDl,
                wifiUploadMbps = 14.2,
                cellularDownloadMbps = cellDl,
                cellularUploadMbps = 9.4,
                combinedDownloadMbps = combinedDl,
                combinedUploadMbps = 22.8,
                wifiRttMs = 16.0,
                cellularRttMs = 34.0,
                combinedRttMs = 17.0,
                gainPercent = 64,
                durationMs = 1500L,
            ),
        )
    }

    override suspend fun probeServers(servers: List<ServerProfile>): List<ServerProbeResult> = withContext(Dispatchers.IO) {
        servers.map { srv ->
            val resolvable = try {
                InetAddress.getByName(srv.host)
                true
            } catch (_: Exception) {
                false
            }
            if (resolvable) {
                ServerProbeResult(
                    serverId = srv.id,
                    wifiRttMs = srv.wifiRttMs ?: 18,
                    cellularRttMs = srv.cellularRttMs ?: 36,
                    reachable = true,
                    quota = srv.quota ?: RelayQuotaInfo(
                        provider = "Oracle Cloud Free",
                        period = "2026-11",
                        usedBytes = 1_319_413_953_331L,
                        limitBytes = 10_995_116_277_760L,
                        remainingBytes = 10_995_116_277_760L - 1_319_413_953_331L,
                        percentUsed = 12.0,
                        activeSessions = 14,
                        resetAtEpochMs = 1_796_083_200_000L,
                    ),
                )
            } else {
                ServerProbeResult(
                    serverId = srv.id,
                    wifiRttMs = null,
                    cellularRttMs = null,
                    reachable = false,
                    error = "Host not found",
                )
            }
        }
    }

    override suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile> = ServerUriCodec.parse(uri)

    override suspend fun getInstalledApps(): List<AppTunnelRule> = listOf(
        AppTunnelRule("com.google.android.youtube", "YouTube", TunnelRulePolicy.BONDED),
        AppTunnelRule("com.discord", "Discord", TunnelRulePolicy.LOW_LATENCY),
        AppTunnelRule("com.valvesoftware.steamlink", "Steam Link", TunnelRulePolicy.LOW_LATENCY),
        AppTunnelRule("org.videolan.vlc", "VLC Media Player", TunnelRulePolicy.BONDED),
        AppTunnelRule("com.spotify.music", "Spotify", TunnelRulePolicy.BYPASS),
    )

    override suspend fun loadPersistedState(): PersistedVelocityState? {
        if (!persistToDisk) return null
        return try {
            val file = File(System.getProperty("user.home"), ".velocity/state.json")
            if (!file.exists()) null else json.decodeFromString<PersistedVelocityState>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun savePersistedState(state: PersistedVelocityState) {
        if (!persistToDisk) return
        try {
            val dir = File(System.getProperty("user.home"), ".velocity")
            dir.mkdirs()
            File(dir, "state.json").writeText(json.encodeToString(state))
        } catch (_: Exception) {}
    }

    override fun copyToClipboard(label: String, text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (_: Throwable) {}
    }

    override fun isSystemDarkTheme(): Boolean = false
}
