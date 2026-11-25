package com.velocity.app.service

import android.util.Log
import com.velocity.app.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * In-process state holder bridging VelocityVpnService and AndroidNetworkMonitor
 * to AndroidPlatformBridge and KMP UI.
 */
object VpnStateHolder {
    private const val TAG = "VpnStateHolder"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val _connectionState = MutableStateFlow(
        ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Ready to bond",
            activeMode = BondingMode.SPEED,
        ),
    )
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(CombinedTelemetry())
    val telemetry: StateFlow<CombinedTelemetry> = _telemetry.asStateFlow()

    private val _networkHealth = MutableStateFlow(NetworkHealthSnapshot())
    val networkHealth: StateFlow<NetworkHealthSnapshot> = _networkHealth.asStateFlow()

    fun updateState(
        state: ConnectionState,
        message: String,
        mode: BondingMode? = null,
        serverId: String? = null,
    ) {
        val current = _connectionState.value
        _connectionState.value = current.copy(
            state = state,
            message = message,
            activeMode = mode ?: current.activeMode,
            activeServerId = serverId ?: current.activeServerId,
            connectedSinceEpochMs = when {
                state == ConnectionState.BONDED && current.connectedSinceEpochMs == null ->
                    System.currentTimeMillis()
                state == ConnectionState.DISCONNECTED -> null
                else -> current.connectedSinceEpochMs
            },
        )
    }

    fun updateTelemetry(combined: CombinedTelemetry) {
        _telemetry.value = combined
    }

    fun updateTelemetryFromJson(jsonString: String) {
        try {
            val parsed = jsonParser.decodeFromString<CombinedTelemetry>(jsonString)
            _telemetry.value = parsed
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry JSON parse failed: ${e.message}")
        }
    }

    fun updateNetworkHealth(snapshot: NetworkHealthSnapshot) {
        _networkHealth.value = snapshot
    }
}
