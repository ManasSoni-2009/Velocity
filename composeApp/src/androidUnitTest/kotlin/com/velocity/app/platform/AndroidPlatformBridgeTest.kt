package com.velocity.app.platform

import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.ConnectionState
import com.velocity.app.domain.LinkType
import com.velocity.app.domain.SpeedTestResult
import com.velocity.app.service.VpnStateHolder
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AndroidPlatformBridgeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testVpnStateObservability() {
        val stateFlow = VpnStateHolder.connectionState
        VpnStateHolder.updateState(
            state = ConnectionState.BONDED,
            message = "Bonded via dual link",
            mode = BondingMode.SPEED,
        )
        assertEquals(ConnectionState.BONDED, stateFlow.value.state)
        assertEquals(BondingMode.SPEED, stateFlow.value.activeMode)

        VpnStateHolder.updateState(
            state = ConnectionState.DISCONNECTED,
            message = "Ready to bond",
            mode = BondingMode.SPEED,
        )
    }

    @Test
    fun testRustCombinedTelemetryJsonContractDecodesCleanly() {
        val rustJson = """
            {
              "timestampMs": 1795591973123,
              "wifi": {
                "pathId": 0,
                "type": "WIFI",
                "interfaceName": "wlan0",
                "active": true,
                "hasInternet": true,
                "isMetered": false,
                "downloadMbps": 54.2,
                "uploadMbps": 12.4,
                "rttMs": 16.5,
                "minRttMs": 14.0,
                "jitterMs": 2.1,
                "lossPercent": 0.1,
                "schedulerWeightPercent": 64,
                "signalDbm": -52,
                "linkSpeedMbps": 433,
                "bytesRx": 102400,
                "bytesTx": 24576
              },
              "cellular": {
                "pathId": 1,
                "type": "CELLULAR",
                "interfaceName": "rmnet0",
                "active": true,
                "hasInternet": true,
                "isMetered": true,
                "downloadMbps": 31.8,
                "uploadMbps": 8.2,
                "rttMs": 36.2,
                "minRttMs": 32.0,
                "jitterMs": 3.8,
                "lossPercent": 0.3,
                "schedulerWeightPercent": 36,
                "signalDbm": -78,
                "linkSpeedMbps": 150,
                "bytesRx": 65536,
                "bytesTx": 16384
              },
              "combinedDownloadMbps": 86.0,
              "combinedUploadMbps": 20.6,
              "effectiveRttMs": 26.35,
              "aggregationEfficiencyPercent": 94,
              "reorderBufferDepth": 3,
              "reorderTimeoutMs": 39,
              "fecRecoveredPackets": 2,
              "fecSentPackets": 5,
              "duplicatedPackets": 1,
              "sessionBytesRx": 167936,
              "sessionBytesTx": 40960,
              "cellularBytesUsedMb": 0.08,
              "activeMode": 0,
              "connectionState": 2,
              "statusMessage": "Bonded · Wi-Fi + Cellular",
              "relayQuota": {
                "provider": "Oracle Cloud Free",
                "period": "2026-11",
                "usedBytes": 1319413953331,
                "limitBytes": 10995116277760,
                "remainingBytes": 9675702324429,
                "percentUsed": 12.0,
                "activeSessions": 14,
                "resetAtEpochMs": 1796083200000,
                "quotaExceeded": false
              }
            }
        """.trimIndent()

        VpnStateHolder.updateTelemetryFromJson(rustJson)
        val parsed = VpnStateHolder.telemetry.value

        assertEquals(86.0, parsed.combinedDownloadMbps, 0.01)
        assertEquals(LinkType.WIFI, parsed.wifi.type)
        assertEquals(54.2, parsed.wifi.downloadMbps, 0.01)
        assertTrue(parsed.wifi.active)
        assertTrue(parsed.cellular.hasInternet)
        assertEquals(94, parsed.aggregationEfficiencyPercent)
        assertEquals("Oracle Cloud Free", parsed.relayQuota?.provider)
        assertEquals("Dec 1", parsed.relayQuota?.resetDateLabel())
        assertEquals("1.2 TB", parsed.relayQuota?.usedFormatted())
        assertEquals("10.0 TB", parsed.relayQuota?.limitFormatted())
    }

    @Test
    fun testRustSpeedTestResultJsonContractDecodesCleanly() {
        val rustSpeedJson = """
            {
              "wifiDownloadMbps": 48.5,
              "wifiUploadMbps": 16.0,
              "cellularDownloadMbps": 32.1,
              "cellularUploadMbps": 9.5,
              "combinedDownloadMbps": 77.4,
              "combinedUploadMbps": 24.8,
              "wifiRttMs": 15.2,
              "cellularRttMs": 34.8,
              "combinedRttMs": 17.5,
              "gainPercent": 60,
              "durationMs": 2800
            }
        """.trimIndent()

        val result = json.decodeFromString<SpeedTestResult>(rustSpeedJson)
        assertEquals(48.5, result.wifiDownloadMbps, 0.01)
        assertEquals(77.4, result.combinedDownloadMbps, 0.01)
        assertEquals(60, result.gainPercent)
    }
}
