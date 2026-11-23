package com.velocity.app.nativebridge

import android.util.Log

/**
 * JNI bindings for Velocity's high-performance Rust multipath core.
 */
object VelocityJni {
    private const val TAG = "VelocityJni"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("velocity_jni")
            isLoaded = true
            Log.i(TAG, "libvelocity_jni loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libvelocity_jni not found or could not be loaded: ${e.message}")
        }
    }

    fun isLibraryLoaded(): Boolean = isLoaded

    interface TelemetryCallback {
        fun onTelemetryUpdate(json: String)
        fun onStateChanged(state: Int, message: String)
    }

    @JvmStatic
    external fun nativeInit(logLevel: Int)

    @JvmStatic
    external fun nativeStartTunnel(
        tunFd: Int,
        configJson: String,
        callback: TelemetryCallback,
    ): Long

    @JvmStatic
    external fun nativeRegisterSocket(
        handle: Long,
        pathId: Int,
        socketFd: Int,
        linkName: String,
        isMetered: Boolean,
    ): Boolean

    @JvmStatic
    external fun nativeUnregisterSocket(handle: Long, pathId: Int)

    @JvmStatic
    external fun nativeUpdateLinkMetrics(
        handle: Long,
        pathId: Int,
        rssiDbm: Int,
        linkSpeedMbps: Int,
    )

    @JvmStatic
    external fun nativeSetMode(handle: Long, mode: Int)

    @JvmStatic
    external fun nativeGetTelemetryJson(handle: Long): String

    @JvmStatic
    external fun nativeRunSpeedTestJson(configJson: String): String

    @JvmStatic
    external fun nativeStopTunnel(handle: Long)
}
