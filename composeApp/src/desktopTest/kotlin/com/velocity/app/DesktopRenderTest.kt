package com.velocity.app

import com.velocity.app.domain.*
import com.velocity.app.platform.DesktopPlatformBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopRenderTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDesktopBridgeLifecycleAndSpeedTest() = runTest {
        val bridge = DesktopPlatformBridge()
        val server = defaultServers.first()

        // 1. Initial State
        assertEquals(ConnectionState.DISCONNECTED, bridge.connectionStateFlow.value.state)

        // 2. Connect
        val connectRes = bridge.connect(
            server = server,
            mode = BondingMode.SPEED,
            settings = VelocitySettings(),
            appRules = emptyList(),
        )
        assertTrue(connectRes.isSuccess)
        assertEquals(ConnectionState.BONDED, bridge.connectionStateFlow.value.state)
        assertEquals(server.id, bridge.connectionStateFlow.value.activeServerId)

        // 3. Switch mode
        val modeRes = bridge.setMode(BondingMode.LOW_LATENCY)
        assertTrue(modeRes.isSuccess)
        assertEquals(BondingMode.LOW_LATENCY, bridge.connectionStateFlow.value.activeMode)

        // 4. Run Speed Test
        var progressCalled = false
        val speedTestRes = bridge.runSpeedTest(server) { phase, fraction, mbps ->
            progressCalled = true
            assertTrue(fraction in 0f..1f)
        }
        assertTrue(speedTestRes.isSuccess)
        assertTrue(progressCalled)
        val result = speedTestRes.getOrThrow()
        assertTrue(result.combinedDownloadMbps > result.wifiDownloadMbps)
        assertTrue(result.gainPercent > 0)

        // 5. Probe Servers
        val probeRes = bridge.probeServers(defaultServers)
        assertEquals(defaultServers.size, probeRes.size)
        assertTrue(probeRes.all { it.reachable })

        // 6. Disconnect
        val disconnectRes = bridge.disconnect()
        assertTrue(disconnectRes.isSuccess)
        assertEquals(ConnectionState.DISCONNECTED, bridge.connectionStateFlow.value.state)
    }

    @Test
    fun testDesktopInstalledApps() = runTest {
        val bridge = DesktopPlatformBridge()
        val apps = bridge.getInstalledApps()
        assertTrue(apps.isNotEmpty())
        assertTrue(apps.any { it.appName == "YouTube" })
    }

    @Test
    fun testDesktopClipboardCopy() {
        val bridge = DesktopPlatformBridge()
        // Should execute without throwing security or platform exceptions
        bridge.copyToClipboard("Test", "velocity://test")
    }

    @Test
    fun testDefaultServersProfileIntegrity() {
        assertEquals(4, defaultServers.size)
        for (server in defaultServers) {
            assertTrue(server.host.isNotBlank())
            assertTrue(server.port in 1..65535)
            assertTrue(server.pskHex.length >= 16)
            assertNotNull(server.region)
        }
    }

    @Test
    fun testDesktopScreenshotHelperExecution() {
        val screenshotsDir = java.io.File("docs/screenshots")
        val capturedMap = DesktopScreenshotHelper.captureAllTabs(screenshotsDir)
        assertEquals(4, capturedMap.size)
        assertTrue(java.io.File(screenshotsDir, "01_bond_tab.png").exists())
        assertTrue(java.io.File(screenshotsDir, "02_telemetry_tab.png").exists())
        assertTrue(java.io.File(screenshotsDir, "03_servers_tab.png").exists())
        assertTrue(java.io.File(screenshotsDir, "04_settings_tab.png").exists())

        // Mirror to root docs/screenshots if running inside composeApp
        val rootDir = java.io.File("../docs/screenshots")
        try {
            rootDir.mkdirs()
            screenshotsDir.listFiles()?.forEach { file ->
                file.copyTo(java.io.File(rootDir, file.name), overwrite = true)
            }
        } catch (_: Throwable) {}
    }
}
