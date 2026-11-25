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
 * and hands the TUN + validated physical network sockets to the Rust multipath core.
 */
class VelocityVpnService : VpnService() {

    companion object {
        private const val TAG = "VelocityVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "velocity_bonding_channel"

        const val ACTION_CONNECT = "com.velocity.app.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.velocity.app.ACTION_DISCONNECT"
        const val ACTION_SET_MODE = "com.velocity.app.ACTION_SET_MODE"
        const val ACTION_UPDATE_TUNING = "com.velocity.app.ACTION_UPDATE_TUNING"

        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_PSK = "extra_psk"
        const val EXTRA_SERVER_ID = "extra_server_id"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_FEC_ENABLED = "extra_fec_enabled"
        const val EXTRA_DIRECT_DNS_GUARD = "extra_direct_dns_guard"
        const val EXTRA_WIFI_BIAS_PERCENT = "extra_wifi_bias_percent"
        const val EXTRA_BYPASS_PACKAGES = "extra_bypass_packages"

        @Volatile
        var isRunning = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var nativeHandle: Long = 0L
    private var multiNetworkManager: MultiNetworkManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentMode = BondingMode.SPEED
    private var currentServerId = ""
    private var lastNotifyMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (VelocityJni.isLibraryLoaded()) {
            try {
                VelocityJni.nativeInit(2)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeInit skipped: ${t.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_SERVER_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_SERVER_PORT, 51820)
                val psk = intent.getStringExtra(EXTRA_PSK).orEmpty()
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: "default"
                val modeWire = intent.getIntExtra(EXTRA_MODE, BondingMode.SPEED.wireValue)
                val mtu = intent.getIntExtra(EXTRA_MTU, 1380)
                val fecEnabled = intent.getBooleanExtra(EXTRA_FEC_ENABLED, true)
                val directDnsGuard = intent.getBooleanExtra(EXTRA_DIRECT_DNS_GUARD, true)
                val wifiBias = intent.getIntExtra(EXTRA_WIFI_BIAS_PERCENT, 70)
                val bypassPackages = intent.getStringArrayListExtra(EXTRA_BYPASS_PACKAGES) ?: arrayListOf()

                currentMode = BondingMode.fromWireValue(modeWire)
                currentServerId = serverId

                startTunnel(
                    host = host,
                    port = port,
                    psk = psk,
                    serverId = serverId,
                    mode = currentMode,
                    mtu = mtu,
                    fecEnabled = fecEnabled,
                    directDnsGuard = directDnsGuard,
                    wifiBiasPercent = wifiBias,
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
                            message = "Mode: ${newMode.label}",
                            mode = newMode,
                        )
                        updateNotification(newMode.label, 0.0, 0.0, force = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set mode: ${e.message}")
                    }
                }
            }
            ACTION_UPDATE_TUNING -> {
                val wifiBias = intent.getIntExtra(EXTRA_WIFI_BIAS_PERCENT, 70)
                val fecEnabled = intent.getBooleanExtra(EXTRA_FEC_ENABLED, true)
                if (nativeHandle != 0L) {
                    try {
                        VelocityJni.nativeUpdateConfig(nativeHandle, wifiBias, fecEnabled)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update tuning: ${e.message}")
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startTunnel(
        host: String,
        port: Int,
        psk: String,
        serverId: String,
        mode: BondingMode,
        mtu: Int,
        fecEnabled: Boolean,
        directDnsGuard: Boolean,
        wifiBiasPercent: Int,
        bypassPackages: List<String>,
    ) {
        stopTunnelInternal(stopServiceAfter = false)

        if (!VelocityJni.isLibraryLoaded()) {
            VpnStateHolder.updateState(
                state = ConnectionState.ERROR,
                message = "Native engine binary missing in build",
                mode = mode,
                serverId = serverId,
            )
            stopSelf()
            return
        }

        if (host.isBlank() || psk.isBlank()) {
            VpnStateHolder.updateState(
                state = ConnectionState.ERROR,
                message = "Relay host and pre-shared key are required",
                mode = mode,
                serverId = serverId,
            )
            stopSelf()
            return
        }

        VpnStateHolder.updateState(
            state = ConnectionState.CONNECTING,
            message = "Connecting to relay...",
            mode = mode,
            serverId = serverId,
        )

        startForeground(NOTIFICATION_ID, buildNotification("Connecting...", 0.0, 0.0))
        isRunning = true

        try {
            val builder = Builder()
                .setSession("Velocity")
                .setMtu(mtu)
                .addAddress("10.66.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setBlocking(true)

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Failed to exclude own package: ${e.message}")
            }

            for (pkg in bypassPackages) {
                if (pkg != packageName) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add bypass app $pkg: ${e.message}")
                    }
                }
            }

            val pfd = builder.establish() ?: throw IOException("Unable to establish VPN interface")
            tunInterface = pfd

            val configJson = JSONObject().apply {
                put("server_host", host.trim())
                put("server_port", port)
                put("psk", psk.trim())
                put("mode", mode.wireValue)
                put("mtu", mtu)
                put("fec_enabled", fecEnabled)
                put("direct_dns_guard", directDnsGuard)
                put("wifi_bias_percent", wifiBiasPercent)
            }.toString()

            val callback = object : VelocityJni.TelemetryCallback {
                override fun onTelemetryUpdate(json: String) {
                    VpnStateHolder.updateTelemetryFromJson(json)
                    val tele = VpnStateHolder.telemetry.value
                    val conn = VpnStateHolder.connectionState.value
                    updateNotification(
                        status = conn.message,
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
                    updateNotification(message, 0.0, 0.0, force = true)
                }
            }

            nativeHandle = VelocityJni.nativeStartTunnel(pfd.fd, configJson, callback)
            if (nativeHandle == 0L) {
                throw IllegalStateException("Failed to initialize native multipath engine")
            }
            Log.i(TAG, "Started native tunnel handle=$nativeHandle")

            AndroidNetworkMonitor.getInstance(this).protectSocketHook = { sock -> protect(sock) }

            multiNetworkManager = MultiNetworkManager(this) { socket -> protect(socket) }
            multiNetworkManager?.start(nativeHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel startup failed: ${e.message}", e)
            VpnStateHolder.updateState(
                state = ConnectionState.ERROR,
                message = e.message ?: "Tunnel startup failed",
                mode = mode,
            )
            stopTunnelInternal(stopServiceAfter = true, preserveErrorState = true)
        }
    }

    private fun stopTunnel() {
        stopTunnelInternal(stopServiceAfter = true, preserveErrorState = false)
    }

    private fun stopTunnelInternal(
        stopServiceAfter: Boolean,
        preserveErrorState: Boolean = false,
    ) {
        if (!isRunning && nativeHandle == 0L && tunInterface == null) return
        Log.i(TAG, "Stopping Velocity tunnel...")

        AndroidNetworkMonitor.getInstance(this).protectSocketHook = null

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
        if (!preserveErrorState) {
            VpnStateHolder.updateState(
                state = ConnectionState.DISCONNECTED,
                message = "Ready to bond",
                mode = currentMode,
            )
        }

        if (stopServiceAfter) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnelInternal(stopServiceAfter = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Velocity Connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows Velocity multipath bonding status"
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

        val speedText = if (downMbps > 0.05 || upMbps > 0.05) {
            "↓ %.1f Mbps  ↑ %.1f Mbps".format(downMbps, upMbps)
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Velocity · $status")
            .setContentText(speedText)
            .setColor(0xFF01249C.toInt())
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

    private fun updateNotification(
        status: String,
        downMbps: Double,
        upMbps: Double,
        force: Boolean = false,
    ) {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < 900L) return
        lastNotifyMs = now
        val notification = buildNotification(status, downMbps, upMbps)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
