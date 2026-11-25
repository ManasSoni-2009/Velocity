package com.velocity.app

import com.velocity.app.domain.*
import com.velocity.app.platform.DesktopPlatformBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopRenderTest {

    private val testServer = ServerProfile(
        id = "srv-local",
        name = "Local Test Relay",
        host = "127.0.0.1",
        port = 51820,
        pskHex = "0123456789abcdef0123456789abcdef",
        region = "Local",
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDesktopBridgeLifecycleAndSpeedTest() = runTest {
        val bridge = DesktopPlatformBridge(persistToDisk = false)

        assertEquals(ConnectionState.DISCONNECTED, bridge.connectionStateFlow.value.state)
        assertTrue(bridge.networkHealthFlow.value.hasAnyInternet)

        val connectRes = bridge.connect(
            server = testServer,
            mode = BondingMode.SPEED,
            settings = VelocitySettings(),
            appRules = emptyList(),
        )
        assertTrue(connectRes.isSuccess)
        assertEquals(ConnectionState.BONDED, bridge.connectionStateFlow.value.state)
        assertEquals(testServer.id, bridge.connectionStateFlow.value.activeServerId)

        val modeRes = bridge.setMode(BondingMode.LOW_LATENCY)
        assertTrue(modeRes.isSuccess)
        assertEquals(BondingMode.LOW_LATENCY, bridge.connectionStateFlow.value.activeMode)

        var progressCalled = false
        val speedTestRes = bridge.runSpeedTest(testServer) { _, fraction, _ ->
            progressCalled = true
            assertTrue(fraction in 0f..1f)
        }
        assertTrue(speedTestRes.isSuccess)
        assertTrue(progressCalled)
        val result = speedTestRes.getOrThrow()
        assertTrue(result.combinedDownloadMbps > result.wifiDownloadMbps)
        assertTrue(result.gainPercent > 0)

        val probeRes = bridge.probeServers(listOf(testServer))
        assertEquals(1, probeRes.size)
        assertTrue(probeRes.first().reachable)

        val disconnectRes = bridge.disconnect()
        assertTrue(disconnectRes.isSuccess)
        assertEquals(ConnectionState.DISCONNECTED, bridge.connectionStateFlow.value.state)
    }

    @Test
    fun testDesktopScreenshotHelperExecution() {
        val screenshotsDir = File("docs/screenshots")
        val capturedMap = DesktopScreenshotHelper.captureAllTabs(screenshotsDir)
        assertEquals(5, capturedMap.size)
        assertTrue(File(screenshotsDir, "01_bond_cream_bonded.png").exists())
        assertTrue(File(screenshotsDir, "02_bond_cream_wifi_no_internet.png").exists())
        assertTrue(File(screenshotsDir, "03_relays_cream.png").exists())
        assertTrue(File(screenshotsDir, "04_settings_cream.png").exists())
        assertTrue(File(screenshotsDir, "05_bond_indigo_dark.png").exists())

        val rootDir = File("../docs/screenshots")
        try {
            rootDir.mkdirs()
            screenshotsDir.listFiles()?.forEach { file ->
                file.copyTo(File(rootDir, file.name), overwrite = true)
            }
        } catch (_: Throwable) {}
    }
}
