package com.velocity.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.velocity.app.MainActivity
import com.velocity.app.domain.BondingMode
import com.velocity.app.domain.ConnectionState
import com.velocity.app.nativebridge.VelocityJni
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

/**
 * Velocity's core Android VPN Service.
 * Establishes local TUN interface (10.66.0.2/24), routes system IP packets,
 * and passes the TUN descriptor to the native multipath bonding engine.
 */
class VelocityVpnService : VpnService() {

    companion object {
        private const val TAG = "VelocityVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "velocity_bonding_channel"

        const val ACTION_CONNECT = "com.velocity.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.velocity.app.ACTION_DISCONNECT"
        const val ACTION_SET_MODE = "com.velocity.app.ACTION_SET_MODE"

        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_PSK_HEX = "extra_psk_hex"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_FEC_ENABLED = "extra_fec_enabled"
        const val EXTRA_BYPASS_PACKAGES = "extra_bypass_packages"

        var isRunning = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var nativeHandle: Long = 0L
    private var multiNetworkManager: MultiNetworkManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentMode = BondingMode.SPEED
    private var currentServerId = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SERVER_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, 51820)
                val pskHex = intent.getStringExtra(EXTRA_PSK_HEX) ?: ""
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: "default"
                val modeWire = intent.getIntExtra(EXTRA_MODE, BondingMode.SPEED.wireValue)
                val mtu = intent.getIntExtra(EXTRA_MTU, 1380)
                val fecEnabled = intent.getBooleanExtra(EXTRA_FEC_ENABLED, true)
                val bypassPackages = intent.getStringArrayListExtra(EXTRA_BYPASS_PACKAGES) ?: arrayListOf()

                currentMode = BondingMode.fromWireValue(modeWire)
                currentServerId = serverId

                startTunnel(
                    host = host,
                    port = port,
                    pskHex = pskHex,
                    serverId = serverId,
                    mode = currentMode,
                    mtu = mtu,
                    fecEnabled = fecEnabled,
                    bypassPackages = bypassPackages,
                )
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
            }
            ACTION_SET_MODE -> {
                val modeWire = intent.getIntExtra(EXTRA_MODE, BondingMode.SPEED.wireValue)
                val newMode = BondingMode.fromWireValue(modeWire)
                currentMode = newMode
                if (nativeHandle != 0L) {
                    try {
                        VelocityJni.nativeSetMode(nativeHandle, newMode.wireValue)
                        VpnStateHolder.updateState(
                            state = VpnStateHolder.connectionState.value.state,
                            message = "Active mode: ${newMode.label}",
                            mode = newMode,
                        )
                        updateNotification("Mode: ${newMode.label}", 0.0, 0.0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set mode: ${e.message}")
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startTunnel(
        host: String,
        port: Int,
        pskHex: String,
        serverId: String,
        mode: BondingMode,
        mtu: Int,
        fecEnabled: Boolean,
        bypassPackages: List<String>,
    ) {
        stopTunnel()

        VpnStateHolder.updateState(
            state = ConnectionState.CONNECTING,
            message = "Establishing multipath tunnel...",
            mode = mode,
            serverId = serverId,
        )

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to $host:$port...", 0.0, 0.0))
        isRunning = true

        try {
            val builder = Builder()
                .setSession("Velocity Multipath Bond")
                .setMtu(mtu)
                .addAddress("10.66.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setBlocking(true)

            // Self-exclusion: disallow Velocity app itself so its UDP multipath sockets don't loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Failed to exclude own package: ${e.message}")
            }

            // Per-app split tunneling
            for (pkg in bypassPackages) {
                if (pkg != packageName) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        Log.d(TAG, "Split-tunnel bypass added: $pkg")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add bypass app $pkg: ${e.message}")
                    }
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                throw IOException("VpnService.Builder.establish() returned null")
            }
            tunInterface = pfd

            val tunFd = pfd.fd

            val configJson = JSONObject().apply {
                put("server_host", host)
                put("server_port", port)
                put("psk_hex", pskHex)
                put("mode", mode.wireValue)
                put("mtu", mtu)
                put("fec_enabled", fecEnabled)
            }.toString()

            val callback = object : VelocityJni.TelemetryCallback {
                override fun onTelemetryUpdate(json: String) {
                    VpnStateHolder.updateTelemetryFromJson(json)
                    val tele = VpnStateHolder.telemetry.value
                    updateNotification(
                        status = "Bonded: ${currentMode.label}",
                        downMbps = tele.combinedDownloadMbps,
                        upMbps = tele.combinedUploadMbps,
                    )
                }

                override fun onStateChanged(state: Int, message: String) {
                    val connState = ConnectionState.fromCode(state)
                    VpnStateHolder.updateState(
                        state = connState,
                        message = message,
                        mode = currentMode,
                    )
                }
            }

            nativeHandle = VelocityJni.nativeStartTunnel(tunFd, configJson, callback)
            Log.i(TAG, "Started native tunnel with handle=$nativeHandle")

            multiNetworkManager = MultiNetworkManager(this) { socket ->
                protect(socket)
            }
            multiNetworkManager?.start(nativeHandle)

            VpnStateHolder.updateState(
                state = ConnectionState.CONNECTING,
                message = "Connecting & probing paths...",
                mode = mode,
                serverId = serverId,
            )
            updateNotification("Connecting...", 0.0, 0.0)

        } catch (e: Exception) {
            Log.e(TAG, "Tunnel startup failed: ${e.message}", e)
            VpnStateHolder.updateState(
                state = ConnectionState.ERROR,
                message = "Startup failed: ${e.message}",
                mode = mode,
            )
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        if (!isRunning && nativeHandle == 0L) return
        Log.i(TAG, "Stopping Velocity tunnel...")

        multiNetworkManager?.stop()
        multiNetworkManager = null

        if (nativeHandle != 0L) {
            try {
                VelocityJni.nativeStopTunnel(nativeHandle)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping native tunnel: ${e.message}")
            }
            nativeHandle = 0L
        }

        try {
            tunInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TUN interface: ${e.message}")
        }
        tunInterface = null

        isRunning = false
        VpnStateHolder.updateState(
            state = ConnectionState.DISCONNECTED,
            message = "Disconnected",
            mode = currentMode,
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Velocity Multipath Bonding",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live throughput and connection status for Velocity multipath bond"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, downMbps: Double, upMbps: Double): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, VelocityVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val speedText = if (downMbps > 0.0 || upMbps > 0.0) {
            " \u2193 %.1f Mbps  \u2191 %.1f Mbps".format(downMbps, upMbps)
        } else {
            ""
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Velocity Multipath Bond \u2014 $status")
            .setContentText(if (speedText.isNotEmpty()) speedText else "Multipath tunnel active")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String, downMbps: Double, upMbps: Double) {
        if (!isRunning) return
        val notification = buildNotification(status, downMbps, upMbps)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
