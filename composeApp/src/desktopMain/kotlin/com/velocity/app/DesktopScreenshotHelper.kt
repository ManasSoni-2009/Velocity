package com.velocity.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.velocity.app.domain.*
import com.velocity.app.platform.DesktopPlatformBridge
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Desktop helper utility for capturing pixel-perfect offscreen screenshots
 * of Velocity's Liquid Glass UI across various viewport dimensions and tabs.
 */
object DesktopScreenshotHelper {

    fun captureTabScreenshot(
        tab: VelocityTab,
        outputFile: File,
        width: Int = 440,
        height: Int = 920,
        density: Density = Density(1f),
        isBonded: Boolean = true,
    ): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            val bridge = DesktopPlatformBridge()
            val vm = BondingViewModel(bridge)
            if (isBonded) {
                runBlocking {
                    bridge.connect(
                        server = defaultServers.first(),
                        mode = BondingMode.SPEED,
                        settings = VelocitySettings(),
                        appRules = emptyList(),
                    )
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
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun captureAllTabs(outputDir: File = File("docs/screenshots")): Map<VelocityTab, File> {
        outputDir.mkdirs()
        val map = mutableMapOf<VelocityTab, File>()
        val tabFiles = listOf(
            VelocityTab.BOND to File(outputDir, "01_bond_tab.png"),
            VelocityTab.TELEMETRY to File(outputDir, "02_telemetry_tab.png"),
            VelocityTab.SERVERS to File(outputDir, "03_servers_tab.png"),
            VelocityTab.SETTINGS to File(outputDir, "04_settings_tab.png"),
        )
        for ((tab, file) in tabFiles) {
            if (captureTabScreenshot(tab, file)) {
                map[tab] = file
            }
        }
        return map
    }

    fun captureAppScreenshot(
        outputFile: File,
        width: Int = 420,
        height: Int = 840,
        density: Density = Density(1f),
    ): Boolean = captureTabScreenshot(
        tab = VelocityTab.BOND,
        outputFile = outputFile,
        width = width,
        height = height,
        density = density,
    )
}
