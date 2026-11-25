package com.velocity.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.velocity.app.domain.*
import com.velocity.app.platform.DesktopPlatformBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Desktop offscreen screenshot utility for visual verification of Velocity's redesigned
 * Pantone Cream & Deep Indigo UI across screens and network states.
 */
object DesktopScreenshotHelper {

    private val sampleOciQuota = RelayQuotaInfo(
        provider = "Oracle Cloud Free",
        period = "2026-11",
        usedBytes = 1_319_413_953_331L,
        limitBytes = 10_995_116_277_760L,
        remainingBytes = 10_995_116_277_760L - 1_319_413_953_331L,
        percentUsed = 12.0,
        activeSessions = 14,
        resetAtEpochMs = 1_796_083_200_000L,
    )

    private val previewServers = listOf(
        ServerProfile(
            id = "srv-frankfurt",
            name = "Oracle Mumbai ARM64",
            host = "127.0.0.1",
            port = 51820,
            pskHex = "0123456789abcdef0123456789abcdef",
            region = "Oracle Cloud",
            wifiRttMs = 16,
            cellularRttMs = 34,
            isReachable = true,
            quota = sampleOciQuota,
        ),
        ServerProfile(
            id = "srv-office",
            name = "Office Relay",
            host = "192.168.1.10",
            port = 51820,
            pskHex = "abcdef0123456789abcdef0123456789",
            region = "Local",
            wifiRttMs = 4,
            cellularRttMs = 28,
            isReachable = true,
        ),
    )

    fun captureTabScreenshot(
        tab: VelocityTab,
        outputFile: File,
        width: Int = 412,
        height: Int = 915,
        density: Density = Density(1f),
        isBonded: Boolean = true,
        themeMode: ThemeMode = ThemeMode.LIGHT,
        wifiNoInternetScenario: Boolean = false,
    ): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            val bridge = DesktopPlatformBridge(persistToDisk = false)
            if (wifiNoInternetScenario) {
                bridge.setSimulatedNetworkHealth(
                    NetworkHealthSnapshot(
                        wifi = LinkHealth(
                            type = LinkType.WIFI,
                            readiness = LinkReadiness.NO_INTERNET,
                            networkLabel = "Wi-Fi",
                            detailText = "No internet",
                            signalBars = 4,
                        ),
                        cellular = LinkHealth(
                            type = LinkType.CELLULAR,
                            readiness = LinkReadiness.ONLINE,
                            networkLabel = "5G",
                            detailText = "Internet ready",
                            isMetered = true,
                            signalBars = 3,
                        ),
                        summaryHeadline = "Wi-Fi has no internet · 5G ready",
                        advisoryMessage = "Wi-Fi is connected without internet access. Velocity will route over 5G until Wi-Fi internet returns.",
                    ),
                )
            }

            val vm = BondingViewModel(bridge, initialServers = previewServers)
            vm.updateSettings { it.copy(themeMode = themeMode) }

            if (isBonded) {
                runBlocking {
                    bridge.connect(
                        server = previewServers.first(),
                        mode = BondingMode.SPEED,
                        settings = VelocitySettings(themeMode = themeMode),
                        appRules = emptyList(),
                    )
                    delay(200)
                }
            }
            vm.selectTab(tab)

            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = density,
            ) {
                VelocityApp(bridge = bridge, viewModel = vm)
            }

            val skiaImage = scene.render()
            val pngBytes = skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return false
            outputFile.writeBytes(pngBytes)
            scene.close()
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    fun captureAllTabs(outputDir: File = File("docs/screenshots")): Map<String, File> {
        outputDir.mkdirs()
        outputDir.listFiles { f -> f.extension == "png" }?.forEach { it.delete() }
        val captures = listOf(
            "01_bond_cream_bonded.png" to {
                captureTabScreenshot(VelocityTab.BOND, File(outputDir, "01_bond_cream_bonded.png"), isBonded = true, themeMode = ThemeMode.LIGHT)
            },
            "02_bond_cream_wifi_no_internet.png" to {
                captureTabScreenshot(VelocityTab.BOND, File(outputDir, "02_bond_cream_wifi_no_internet.png"), isBonded = false, themeMode = ThemeMode.LIGHT, wifiNoInternetScenario = true)
            },
            "03_relays_cream.png" to {
                captureTabScreenshot(VelocityTab.RELAYS, File(outputDir, "03_relays_cream.png"), isBonded = true, themeMode = ThemeMode.LIGHT)
            },
            "04_settings_cream.png" to {
                captureTabScreenshot(VelocityTab.SETTINGS, File(outputDir, "04_settings_cream.png"), isBonded = true, themeMode = ThemeMode.LIGHT)
            },
            "05_bond_indigo_dark.png" to {
                captureTabScreenshot(VelocityTab.BOND, File(outputDir, "05_bond_indigo_dark.png"), isBonded = true, themeMode = ThemeMode.DARK)
            },
        )

        val map = linkedMapOf<String, File>()
        for ((name, fn) in captures) {
            if (fn()) {
                map[name] = File(outputDir, name)
            }
        }
        return map
    }
}
