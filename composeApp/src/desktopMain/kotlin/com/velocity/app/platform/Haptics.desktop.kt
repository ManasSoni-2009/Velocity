package com.velocity.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberHapticPerformer(): (VelocityHaptic) -> Unit = remember {
    { _ -> }
}
