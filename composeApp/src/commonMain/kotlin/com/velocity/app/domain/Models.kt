package com.velocity.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Multipath packet scheduling modes supported by Velocity's Rust core.
 * Plain-English labels keep the primary UI effortless while mapping 1:1 to wire values 0..2.
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
        label = "Fastest",
        shortLabel = "Fastest",
        subtitle = "Combines Wi-Fi and Cellular for maximum speed",
    ),
    LOW_LATENCY(
        wireValue = 1,
        label = "Low Lag",
        shortLabel = "Low Lag",
        subtitle = "Favors your lowest-ping connection for smooth calls & gaming",
    ),
    FAILOVER(
        wireValue = 2,
        label = "Backup",
        shortLabel = "Backup",
        subtitle = "Stays on Wi-Fi and switches automatically if it drops",
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
    DISCONNECTED(0, "Ready"),
    CONNECTING(1, "Connecting"),
    BONDED(2, "Bonded"),
    RECONNECTING(3, "Reconnecting"),
    ERROR(4, "Attention");

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
    val message: String = "Tap to bond available networks",
    val activeMode: BondingMode = BondingMode.SPEED,
    val connectedSinceEpochMs: Long? = null,
    val activeServerId: String? = null,
)

@Serializable
enum class LinkType(val pathId: Int, val displayName: String) {
    WIFI(0, "Wi-Fi"),
    CELLULAR(1, "Cellular"),
}

/**
 * Real-world physical + internet readiness state for a single radio interface.
 */
@Serializable
enum class LinkReadiness(val label: String) {
    UNAVAILABLE("Off"),
    CONNECTING("Connecting"),
    CHECKING("Checking"),
    NO_INTERNET("No internet"),
    CAPTIVE_PORTAL("Sign in needed"),
    ONLINE("Online"),
    BONDED("Active");

    val hasInternet: Boolean
        get() = this == ONLINE || this == BONDED

    val isConnectedPhysically: Boolean
        get() = this != UNAVAILABLE
}

@Serializable
data class LinkHealth(
    val type: LinkType,
    val readiness: LinkReadiness = LinkReadiness.UNAVAILABLE,
    val networkLabel: String = type.displayName,
    val detailText: String = readiness.label,
    val isMetered: Boolean = type == LinkType.CELLULAR,
    val signalBars: Int = 0,
    val signalDbm: Int = -120,
    val linkSpeedMbps: Int = 0,
    val downloadMbps: Double = 0.0,
    val rttMs: Double = 0.0,
)

@Serializable
data class NetworkHealthSnapshot(
    val wifi: LinkHealth = LinkHealth(type = LinkType.WIFI),
    val cellular: LinkHealth = LinkHealth(type = LinkType.CELLULAR, isMetered = true),
    val summaryHeadline: String = "Checking connections...",
    val advisoryMessage: String? = null,
    val lastUpdatedMs: Long = 0L,
) {
    val hasAnyInternet: Boolean
        get() = wifi.readiness.hasInternet || cellular.readiness.hasInternet

    val onlineLinkCount: Int
        get() = (if (wifi.readiness.hasInternet) 1 else 0) +
            (if (cellular.readiness.hasInternet) 1 else 0)
}

@Serializable
data class LinkTelemetry(
    val pathId: Int = 0,
    val type: LinkType = LinkType.WIFI,
    val interfaceName: String = "",
    val active: Boolean = false,
    val hasInternet: Boolean = false,
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
data class RelayQuotaInfo(
    val provider: String = "Oracle Cloud Free",
    val period: String = "",
    val usedBytes: Long = 0L,
    val clientEgressBytes: Long = 0L,
    val wanEgressBytes: Long = 0L,
    val limitBytes: Long = 0L,
    val remainingBytes: Long = 0L,
    val percentUsed: Double = 0.0,
    val activeSessions: Int = 0,
    val resetAtEpochMs: Long = 0L,
    val quotaExceeded: Boolean = false,
) {
    val hasValidLimit: Boolean
        get() = limitBytes > 0L

    val fractionUsed: Float
        get() = if (limitBytes <= 0L) 0f else (usedBytes.toDouble() / limitBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    fun usedFormatted(): String = formatBytesHuman(usedBytes)
    fun limitFormatted(): String = formatBytesHuman(limitBytes)
    fun remainingFormatted(): String = formatBytesHuman(remainingBytes)
    fun resetDateLabel(): String = formatUtcMonthDay(resetAtEpochMs)
}

fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0
    val (value, unit) = when {
        bytes >= tb -> (bytes / tb) to "TB"
        bytes >= gb -> (bytes / gb) to "GB"
        bytes >= mb -> (bytes / mb) to "MB"
        else -> (bytes / kb).coerceAtLeast(1.0) to "KB"
    }
    val rounded = ((value * 10.0).roundToInt() / 10.0)
    return "$rounded $unit"
}

private fun formatUtcMonthDay(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val days = (epochMs / 86_400_000L)
    var z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460L + doe / 36_524L - doe / 146_096L) / 365L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()
    val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val mName = months.getOrElse(m - 1) { "Jan" }
    return "$mName $d"
}

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
    val reorderTimeoutMs: Int = 15,
    val fecRecoveredPackets: Long = 0L,
    val fecSentPackets: Long = 0L,
    val duplicatedPackets: Long = 0L,
    val sessionBytesRx: Long = 0L,
    val sessionBytesTx: Long = 0L,
    val cellularBytesUsedMb: Double = 0.0,
    val activeMode: Int = 0,
    val connectionState: Int = 0,
    val statusMessage: String = "Ready",
    val relayQuota: RelayQuotaInfo? = null,
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
    val lastProbeError: String? = null,
    val isSelfHosted: Boolean = true,
    val quota: RelayQuotaInfo? = null,
)

@Serializable
enum class TunnelRulePolicy(val label: String) {
    BONDED("Bonded"),
    LOW_LATENCY("Low Lag"),
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
    SYSTEM("Auto"),
    LIGHT("Lilac"),
    DARK("Obsidian"),
}

@Serializable
data class VelocitySettings(
    val cellularDataCapMb: Int = 5120,
    val wifiBiasPercent: Int = 70,
    val mtu: Int = 1380,
    val fecEnabled: Boolean = true,
    val directDnsGuard: Boolean = true,
    val autoFallbackOnNoInternet: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

@Serializable
data class PersistedVelocityState(
    val servers: List<ServerProfile> = emptyList(),
    val selectedServerId: String? = null,
    val settings: VelocitySettings = VelocitySettings(),
    val appRulePolicies: Map<String, TunnelRulePolicy> = emptyMap(),
    val activeMode: BondingMode = BondingMode.SPEED,
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
    val error: String? = null,
    val quota: RelayQuotaInfo? = null,
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
    RELAYS("Relays"),
    SETTINGS("Settings"),
}
