package com.velocity.app.domain

import kotlinx.serialization.Serializable

/**
 * Multipath packet scheduling modes supported by Velocity's Rust core.
 */
@Serializable
enum class BondingMode(
    val wireValue: Int,
    val label: String,
    val shortLabel: String,
    val subtitle: String,
) {
    SPEED(
        wireValue = 0,
        label = "Speed",
        shortLabel = "Speed",
        subtitle = "Arrival-compensated BDP bonding",
    ),
    LOW_LATENCY(
        wireValue = 1,
        label = "Low-Latency",
        shortLabel = "Low-Lat",
        subtitle = "Min-RTT + adaptive FEC / duplication",
    ),
    FAILOVER(
        wireValue = 2,
        label = "Failover",
        shortLabel = "Failover",
        subtitle = "Wi-Fi primary + 250ms cellular standby",
    );

    companion object {
        fun fromWireValue(value: Int): BondingMode =
            entries.firstOrNull { it.wireValue == value } ?: SPEED
    }
}

/**
 * Tunnel lifecycle state matching native `VelocityJni` state codes (0..4).
 */
@Serializable
enum class ConnectionState(val code: Int, val label: String) {
    DISCONNECTED(0, "Disconnected"),
    CONNECTING(1, "Connecting"),
    BONDED(2, "Bonded"),
    RECONNECTING(3, "Reconnecting"),
    ERROR(4, "Error");

    val isActiveOrTransitioning: Boolean
        get() = this == CONNECTING || this == BONDED || this == RECONNECTING

    companion object {
        fun fromCode(code: Int): ConnectionState =
            entries.firstOrNull { it.code == code } ?: DISCONNECTED
    }
}

@Serializable
data class ConnectionStatus(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val message: String = "Ready to bond",
    val activeMode: BondingMode = BondingMode.SPEED,
    val connectedSinceEpochMs: Long? = null,
    val activeServerId: String? = null,
)

@Serializable
enum class LinkType(val pathId: Int, val displayName: String) {
    WIFI(0, "Wi-Fi"),
    CELLULAR(1, "Cellular"),
}

@Serializable
data class LinkTelemetry(
    val pathId: Int,
    val type: LinkType,
    val interfaceName: String = "",
    val active: Boolean = false,
    val isMetered: Boolean = false,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val rttMs: Double = 0.0,
    val minRttMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val lossPercent: Double = 0.0,
    val schedulerWeightPercent: Int = 0,
    val signalDbm: Int = -120,
    val linkSpeedMbps: Int = 0,
    val bytesRx: Long = 0L,
    val bytesTx: Long = 0L,
)

@Serializable
data class CombinedTelemetry(
    val timestampMs: Long = 0L,
    val wifi: LinkTelemetry = LinkTelemetry(
        pathId = 0,
        type = LinkType.WIFI,
        interfaceName = "wlan0",
    ),
    val cellular: LinkTelemetry = LinkTelemetry(
        pathId = 1,
        type = LinkType.CELLULAR,
        interfaceName = "rmnet0",
        isMetered = true,
    ),
    val combinedDownloadMbps: Double = 0.0,
    val combinedUploadMbps: Double = 0.0,
    val effectiveRttMs: Double = 0.0,
    val aggregationEfficiencyPercent: Int = 0,
    val reorderBufferDepth: Int = 0,
    val reorderTimeoutMs: Int = 25,
    val fecRecoveredPackets: Long = 0L,
    val fecSentPackets: Long = 0L,
    val duplicatedPackets: Long = 0L,
    val sessionBytesRx: Long = 0L,
    val sessionBytesTx: Long = 0L,
    val cellularBytesUsedMb: Double = 0.0,
)

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 51820,
    val pskHex: String,
    val region: String = "Custom",
    val mtu: Int = 1380,
    val wifiRttMs: Int? = null,
    val cellularRttMs: Int? = null,
    val isReachable: Boolean? = null,
    val isSelfHosted: Boolean = false,
)

@Serializable
enum class TunnelRulePolicy(val label: String) {
    BONDED("Bonded"),
    LOW_LATENCY("Low-Latency"),
    BYPASS("Bypass"),
}

@Serializable
data class AppTunnelRule(
    val packageName: String,
    val appName: String,
    val policy: TunnelRulePolicy = TunnelRulePolicy.BONDED,
    val isSystemApp: Boolean = false,
)

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Obsidian"),
    LIGHT("Daylight"),
}

@Serializable
data class VelocitySettings(
    val cellularDataCapMb: Int = 5120,
    val wifiBiasPercent: Int = 70,
    val mtu: Int = 1380,
    val fecEnabled: Boolean = true,
    val directDnsGuard: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
)

@Serializable
data class SpeedTestResult(
    val wifiDownloadMbps: Double,
    val wifiUploadMbps: Double,
    val cellularDownloadMbps: Double,
    val cellularUploadMbps: Double,
    val combinedDownloadMbps: Double,
    val combinedUploadMbps: Double,
    val wifiRttMs: Double,
    val cellularRttMs: Double,
    val combinedRttMs: Double,
    val gainPercent: Int,
    val durationMs: Long,
)

@Serializable
data class ServerProbeResult(
    val serverId: String,
    val wifiRttMs: Int?,
    val cellularRttMs: Int?,
    val reachable: Boolean,
)

data class SpeedTestProgress(
    val isRunning: Boolean = false,
    val phase: String = "Idle",
    val progressFraction: Float = 0f,
    val liveCombinedMbps: Double = 0.0,
    val result: SpeedTestResult? = null,
    val errorMessage: String? = null,
)

data class TelemetrySample(
    val wifiDownloadMbps: Float,
    val cellularDownloadMbps: Float,
    val combinedDownloadMbps: Float,
    val wifiUploadMbps: Float,
    val cellularUploadMbps: Float,
    val combinedUploadMbps: Float,
    val wifiRttMs: Float,
    val cellularRttMs: Float,
    val effectiveRttMs: Float,
)

enum class VelocityTab(val title: String) {
    BOND("Bond"),
    TELEMETRY("Telemetry"),
    SERVERS("Servers"),
    SETTINGS("Settings"),
}
