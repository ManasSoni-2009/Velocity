package com.velocity.app.platform

import androidx.compose.runtime.Composable

enum class VelocityHaptic {
    TAP,
    CONFIRM,
    REJECT,
    TICK,
}

@Composable
expect fun rememberHapticPerformer(): (VelocityHaptic) -> Unit
