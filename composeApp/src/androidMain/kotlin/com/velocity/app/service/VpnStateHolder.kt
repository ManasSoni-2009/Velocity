package com.velocity.app.service

import com.velocity.app.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * In-process state holder bridging VelocityVpnService events to AndroidPlatformBridge and KMP UI.
 */
object VpnStateHolder {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _connectionState = MutableStateFlow(
        ConnectionStatus(
            state = ConnectionState.DISCONNECTED,
            message = "Ready to bond",
            activeMode = BondingMode.SPEED,
        )
    )
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(CombinedTelemetry())
    val telemetry: StateFlow<CombinedTelemetry> = _telemetry.asStateFlow()

    fun updateState(state: ConnectionState, message: String, mode: BondingMode? = null, serverId: String? = null) {
        val current = _connectionState.value
        _connectionState.value = current.copy(
            state = state,
            message = message,
            activeMode = mode ?: current.activeMode,
            activeServerId = serverId ?: current.activeServerId,
            connectedSinceEpochMs = if (state == ConnectionState.BONDED && current.connectedSinceEpochMs == null) {
                System.currentTimeMillis()
            } else if (state == ConnectionState.DISCONNECTED) {
                null
            } else {
                current.connectedSinceEpochMs
            }
        )
    }

    fun updateTelemetry(combined: CombinedTelemetry) {
        _telemetry.value = combined
    }

    fun updateTelemetryFromJson(jsonString: String) {
        try {
            val telemetry = jsonParser.decodeFromString<CombinedTelemetry>(jsonString)
            _telemetry.value = telemetry
        } catch (e: Exception) {
            // If json format is custom or simplified from Rust core, parse safely
        }
    }
}
