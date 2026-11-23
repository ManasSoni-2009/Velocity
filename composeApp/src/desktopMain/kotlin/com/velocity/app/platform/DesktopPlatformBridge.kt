package com.velocity.app.platform

import com.velocity.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.sin
import kotlin.random.Random

/**
 * JVM Desktop standalone implementation of VelocityPlatformBridge.
 * Provides realistic live dual-link multipath bonding telemetry simulation,
 * interactive speed tests, server probes, and clipboard integration.
 */
class DesktopPlatformBridge(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : VelocityPlatformBridge {

    private val _telemetryFlow = MutableStateFlow(CombinedTelemetry())
    override val telemetryFlow: StateFlow<CombinedTelemetry> = _telemetryFlow.asStateFlow()

    private val _connectionStateFlow = MutableStateFlow(
        ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Ready to bond",
            activeMode = BondingMode.SPEED,
        ),
    )
    override val connectionStateFlow: StateFlow<ConnectionStatus> = _connectionStateFlow.asStateFlow()

    private var telemetrySimulationJob: Job? = null
    private var simulatedBytesRx = 0L
    private var simulatedBytesTx = 0L
    private var currentMode = BondingMode.SPEED

    init {
        startTelemetrySimulation()
    }

    private fun startTelemetrySimulation() {
        telemetrySimulationJob?.cancel()
        telemetrySimulationJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(100) // 10Hz telemetry updates
                tick++
                val isBonded = _connectionStateFlow.value.state == ConnectionState.BONDED

                val (wifiMbps, cellMbps, effRtt) = if (isBonded) {
                    val oscillation = sin(tick * 0.1) * 6.0
                    val baseWifi = 48.0 + oscillation + Random.nextDouble(-2.0, 2.0)
                    val baseCell = 34.0 - oscillation * 0.5 + Random.nextDouble(-3.0, 3.0)

                    when (currentMode) {
                        BondingMode.SPEED -> Triple(baseWifi, baseCell, 22.0)
                        BondingMode.LOW_LATENCY -> Triple(baseWifi * 0.85, baseCell * 0.85, 14.5)
                        BondingMode.FAILOVER -> Triple(baseWifi, 0.5, 19.0)
                    }
                } else {
                    Triple(0.0, 0.0, 0.0)
                }

                val combinedMbps = if (isBonded) {
                    when (currentMode) {
                        BondingMode.SPEED -> (wifiMbps + cellMbps) * 0.92
                        BondingMode.LOW_LATENCY -> maxOf(wifiMbps, cellMbps) * 1.05
                        BondingMode.FAILOVER -> wifiMbps
                    }
                } else {
                    0.0
                }

                if (isBonded) {
                    simulatedBytesRx += (combinedMbps * 1024 * 1024 / 8 / 10).toLong()
                    simulatedBytesTx += (combinedMbps * 1024 * 1024 / 8 / 40).toLong()
                }

                val wifiWeight = if (isBonded && wifiMbps + cellMbps > 0) {
                    ((wifiMbps / (wifiMbps + cellMbps)) * 100).toInt()
                } else {
                    50
                }

                val tel = CombinedTelemetry(
                    timestampMs = System.currentTimeMillis(),
                    wifi = LinkTelemetry(
                        pathId = 0,
                        type = LinkType.WIFI,
                        interfaceName = "wlan0",
                        active = isBonded,
                        downloadMbps = wifiMbps,
                        uploadMbps = wifiMbps * 0.25,
                        rttMs = if (isBonded) 18.0 + Random.nextDouble(-1.5, 2.0) else 0.0,
                        jitterMs = if (isBonded) 2.2 else 0.0,
                        lossPercent = if (isBonded) 0.1 else 0.0,
                        schedulerWeightPercent = wifiWeight,
                        signalDbm = -54,
                        linkSpeedMbps = 433,
                    ),
                    cellular = LinkTelemetry(
                        pathId = 1,
                        type = LinkType.CELLULAR,
                        interfaceName = "rmnet0",
                        active = isBonded && currentMode != BondingMode.FAILOVER,
                        isMetered = true,
                        downloadMbps = cellMbps,
                        uploadMbps = cellMbps * 0.20,
                        rttMs = if (isBonded) 36.0 + Random.nextDouble(-3.0, 4.0) else 0.0,
                        jitterMs = if (isBonded) 4.1 else 0.0,
                        lossPercent = if (isBonded) 0.3 else 0.0,
                        schedulerWeightPercent = 100 - wifiWeight,
                        signalDbm = -78,
                        linkSpeedMbps = 150,
                    ),
                    combinedDownloadMbps = combinedMbps,
                    combinedUploadMbps = combinedMbps * 0.22,
                    effectiveRttMs = effRtt,
                    aggregationEfficiencyPercent = if (isBonded) 91 else 0,
                    reorderBufferDepth = if (isBonded) 4 else 0,
                    reorderTimeoutMs = 24,
                    fecRecoveredPackets = if (isBonded) (tick / 4).toLong() else 0L,
                    fecSentPackets = if (isBonded) (tick / 2).toLong() else 0L,
                    duplicatedPackets = if (isBonded && currentMode == BondingMode.LOW_LATENCY) (tick * 2).toLong() else 0L,
                    sessionBytesRx = simulatedBytesRx,
                    sessionBytesTx = simulatedBytesTx,
                    cellularBytesUsedMb = (simulatedBytesRx * 0.38) / (1024 * 1024),
                )

                _telemetryFlow.value = tel
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
            message = "Negotiating dual-path handshake with ${server.name}...",
            activeMode = mode,
            activeServerId = server.id,
        )

        delay(400) // Brief handshake delay

        _connectionStateFlow.value = ConnectionStatus(
            state = ConnectionState.BONDED,
            message = "Bonded across Wi-Fi & Cellular (${mode.label})",
            activeMode = mode,
            connectedSinceEpochMs = System.currentTimeMillis(),
            activeServerId = server.id,
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        _connectionStateFlow.value = ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Disconnected",
            activeMode = currentMode,
        )
        return Result.success(Unit)
    }

    override suspend fun setMode(mode: BondingMode): Result<Unit> {
        currentMode = mode
        val current = _connectionStateFlow.value
        _connectionStateFlow.value = current.copy(
            activeMode = mode,
            message = if (current.state == ConnectionState.BONDED) {
                "Switched to ${mode.label} bonding"
            } else {
                current.message
            },
        )
        return Result.success(Unit)
    }

    override suspend fun updateSplitTunnelApps(rules: List<AppTunnelRule>): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun runSpeedTest(
        server: ServerProfile,
        onProgress: (phase: String, fraction: Float, liveMbps: Double) -> Unit,
    ): Result<SpeedTestResult> = withContext(Dispatchers.Default) {
        // Phase 1: Wi-Fi Only Test
            for (i in 1..10) {
                delay(80)
                val mbps = 44.0 + Random.nextDouble(-3.0, 4.0)
                onProgress("Benchmarking Path 0 (Wi-Fi)...", i * 0.03f, mbps)
            }
            val wifiDl = 46.8
            val wifiUl = 12.4
            val wifiRtt = 18.0

            // Phase 2: Cellular Only Test
            for (i in 1..10) {
                delay(80)
                val mbps = 33.0 + Random.nextDouble(-4.0, 3.0)
                onProgress("Benchmarking Path 1 (Cellular 5G)...", 0.30f + (i * 0.03f), mbps)
            }
            val cellDl = 34.2
            val cellUl = 8.6
            val cellRtt = 38.0

            // Phase 3: Combined Dual-Path Bonding Test
            for (i in 1..14) {
                delay(80)
                val mbps = 75.0 + (i * 0.5) + Random.nextDouble(-2.0, 2.0)
                onProgress("Benchmarking Multi-Link BDP Bonding...", 0.60f + (i * 0.025f), mbps)
            }
            val combinedDl = 76.5
            val combinedUl = 19.8
            val combinedRtt = 17.0

            onProgress("Finalizing Multipath Benchmark...", 1.0f, combinedDl)

            val gainPercent = (((combinedDl - maxOf(wifiDl, cellDl)) / maxOf(wifiDl, cellDl)) * 100).toInt()

            Result.success(
                SpeedTestResult(
                    wifiDownloadMbps = wifiDl,
                    wifiUploadMbps = wifiUl,
                    cellularDownloadMbps = cellDl,
                    cellularUploadMbps = cellUl,
                    combinedDownloadMbps = combinedDl,
                    combinedUploadMbps = combinedUl,
                    wifiRttMs = wifiRtt,
                    cellularRttMs = cellRtt,
                    combinedRttMs = combinedRtt,
                    gainPercent = gainPercent,
                    durationMs = 3200L,
                ),
            )
        }

    override suspend fun probeServers(servers: List<ServerProfile>): List<ServerProbeResult> {
        delay(300)
        return servers.map { srv ->
            ServerProbeResult(
                serverId = srv.id,
                wifiRttMs = srv.wifiRttMs ?: (15 + Random.nextInt(30)),
                cellularRttMs = srv.cellularRttMs ?: (32 + Random.nextInt(40)),
                reachable = true,
            )
        }
    }

    override suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile> {
        return ServerUriCodec.parse(uri)
    }

    override suspend fun getInstalledApps(): List<AppTunnelRule> {
        return listOf(
            AppTunnelRule("com.google.android.youtube", "YouTube", TunnelRulePolicy.BONDED),
            AppTunnelRule("com.discord", "Discord", TunnelRulePolicy.LOW_LATENCY),
            AppTunnelRule("com.valvesoftware.steamlink", "Steam Link", TunnelRulePolicy.LOW_LATENCY),
            AppTunnelRule("org.videolan.vlc", "VLC Media Player", TunnelRulePolicy.BONDED),
            AppTunnelRule("com.spotify.music", "Spotify", TunnelRulePolicy.BYPASS),
            AppTunnelRule("com.netflix.mediaclient", "Netflix", TunnelRulePolicy.BONDED),
        )
    }

    override fun copyToClipboard(label: String, text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (_: Throwable) {
            // Clipboard access fallback in headless or sandboxed environments
        }
    }

    override fun isSystemDarkTheme(): Boolean = true
}
