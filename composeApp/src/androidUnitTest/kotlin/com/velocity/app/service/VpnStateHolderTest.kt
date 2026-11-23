package com.velocity.app.service

import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.ConnectionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VpnStateHolderTest {

    @Before
    fun setUp() {
        VpnStateHolder.updateState(
            state = ConnectionState.DISCONNECTED,
            message = "Ready to bond",
            mode = BondingMode.SPEED,
        )
    }

    @Test
    fun testInitialState() {
        val state = VpnStateHolder.connectionState.value
        assertEquals(ConnectionState.DISCONNECTED, state.state)
        assertEquals("Ready to bond", state.message)
        assertNull(state.connectedSinceEpochMs)
    }

    @Test
    fun testConnectionTransitions() {
        VpnStateHolder.updateState(
            state = ConnectionState.CONNECTING,
            message = "Connecting to relay...",
            mode = BondingMode.SPEED,
            serverId = "srv-us-east",
        )
        var state = VpnStateHolder.connectionState.value
        assertEquals(ConnectionState.CONNECTING, state.state)
        assertEquals("srv-us-east", state.activeServerId)

        VpnStateHolder.updateState(
            state = ConnectionState.BONDED,
            message = "Bonded",
            mode = BondingMode.SPEED,
        )
        state = VpnStateHolder.connectionState.value
        assertEquals(ConnectionState.BONDED, state.state)
        assertNotNull(state.connectedSinceEpochMs)

        VpnStateHolder.updateState(
            state = ConnectionState.DISCONNECTED,
            message = "Disconnected",
        )
        state = VpnStateHolder.connectionState.value
        assertEquals(ConnectionState.DISCONNECTED, state.state)
        assertNull(state.connectedSinceEpochMs)
    }

    @Test
    fun testTelemetryJsonUpdate() {
        val sampleJson = """
            {
                "timestampMs": 1700000000,
                "combinedDownloadMbps": 142.5,
                "combinedUploadMbps": 48.2,
                "effectiveRttMs": 18.5,
                "aggregationEfficiencyPercent": 92,
                "reorderBufferDepth": 4,
                "reorderTimeoutMs": 20,
                "fecRecoveredPackets": 12,
                "fecSentPackets": 450,
                "duplicatedPackets": 5,
                "sessionBytesRx": 10485760,
                "sessionBytesTx": 5242880,
                "cellularBytesUsedMb": 4.5,
                "wifi": {
                    "pathId": 0,
                    "type": "WIFI",
                    "interfaceName": "wlan0",
                    "active": true,
                    "downloadMbps": 95.0,
                    "uploadMbps": 30.0,
                    "rttMs": 16.0,
                    "lossPercent": 0.2
                },
                "cellular": {
                    "pathId": 1,
                    "type": "CELLULAR",
                    "interfaceName": "rmnet0",
                    "active": true,
                    "isMetered": true,
                    "downloadMbps": 50.0,
                    "uploadMbps": 20.0,
                    "rttMs": 26.0,
                    "lossPercent": 0.5
                }
            }
        """.trimIndent()

        VpnStateHolder.updateTelemetryFromJson(sampleJson)
        val telemetry = VpnStateHolder.telemetry.value
        assertEquals(142.5, telemetry.combinedDownloadMbps, 0.01)
        assertEquals(48.2, telemetry.combinedUploadMbps, 0.01)
        assertEquals(18.5, telemetry.effectiveRttMs, 0.01)
        assertEquals(92, telemetry.aggregationEfficiencyPercent)
        assertEquals(95.0, telemetry.wifi.downloadMbps, 0.01)
        assertEquals(50.0, telemetry.cellular.downloadMbps, 0.01)
    }
}
