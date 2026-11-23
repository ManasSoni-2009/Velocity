package com.velocity.app.platform

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.velocity.app.domain.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform bridge interface connecting shared KMP UI & state
 * to the underlying platform runtime (Android VpnService + native Rust JNI,
 * or Desktop JVM standalone simulated/socket engine).
 */
interface VelocityPlatformBridge {
    val telemetryFlow: StateFlow<CombinedTelemetry>
    val connectionStateFlow: StateFlow<ConnectionStatus>

    suspend fun connect(
        server: ServerProfile,
        mode: BondingMode,
        settings: VelocitySettings,
        appRules: List<AppTunnelRule>,
    ): Result<Unit>

    suspend fun disconnect(): Result<Unit>

    suspend fun setMode(mode: BondingMode): Result<Unit>

    suspend fun updateSplitTunnelApps(rules: List<AppTunnelRule>): Result<Unit>

    suspend fun runSpeedTest(
        server: ServerProfile,
        onProgress: (phase: String, fraction: Float, liveMbps: Double) -> Unit = { _, _, _ -> },
    ): Result<SpeedTestResult>

    suspend fun probeServers(servers: List<ServerProfile>): List<ServerProbeResult>

    suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile>

    suspend fun getInstalledApps(): List<AppTunnelRule>

    fun copyToClipboard(label: String, text: String)

    fun isSystemDarkTheme(): Boolean = true
}

/**
 * Expect composable for authentic Apple Liquid Glass HIG surface.
 * Desktop implements this via Skiko SkSL RuntimeShader backdrop blur + refraction.
 * Android implements this via AGSL RuntimeShader (API 33+) or blur/transparency fallback.
 */
@Composable
expect fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    elevation: Dp = 12.dp,
    reduceTransparency: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
)
