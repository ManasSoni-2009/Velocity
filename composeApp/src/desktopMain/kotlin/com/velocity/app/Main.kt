package com.velocity.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.velocity.app.platform.DesktopPlatformBridge

fun main() = application {
    val windowState = rememberWindowState(
        width = 440.dp,
        height = 880.dp,
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Velocity - Multipath Bonding Engine",
        state = windowState,
    ) {
        val bridge = rememberDesktopBridge()
        VelocityApp(bridge = bridge)
    }
}

private var cachedBridge: DesktopPlatformBridge? = null

private fun rememberDesktopBridge(): DesktopPlatformBridge {
    return cachedBridge ?: DesktopPlatformBridge().also { cachedBridge = it }
}
