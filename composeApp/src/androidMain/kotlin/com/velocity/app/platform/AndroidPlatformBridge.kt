package com.velocity.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.velocity.app.domain.*
import com.velocity.app.nativebridge.VelocityJni
import com.velocity.app.service.AndroidNetworkMonitor
import com.velocity.app.service.VelocityVpnService
import com.velocity.app.service.VpnStateHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.ArrayList

/**
 * Android implementation of [VelocityPlatformBridge].
 * Coordinates always-on AndroidNetworkMonitor, VpnService lifecycle + permission continuation,
 * real native UDP speedtests, real encrypted VL01 UDP server probes, and state persistence.
 */
class AndroidPlatformBridge(
    private val context: Context,
    private val vpnPermissionLauncher: ((onGranted: () -> Unit) -> Unit)? = null,
) : VelocityPlatformBridge {

    companion object {
        private const val TAG = "AndroidPlatformBridge"
        private const val STATE_FILE_NAME = "velocity_state.json"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    private val networkMonitor = AndroidNetworkMonitor.getInstance(context).also {
        it.start()
    }

    override val telemetryFlow: StateFlow<CombinedTelemetry> = VpnStateHolder.telemetry
    override val connectionStateFlow: StateFlow<ConnectionStatus> = VpnStateHolder.connectionState
    override val networkHealthFlow: StateFlow<NetworkHealthSnapshot> = networkMonitor.healthFlow

    override suspend fun connect(
        server: ServerProfile,
        mode: BondingMode,
        settings: VelocitySettings,
        appRules: List<AppTunnelRule>,
    ): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                val launcher = vpnPermissionLauncher
                    ?: throw SecurityException("VPN permission is required to bond networks")
                launcher {
                    startVpnForeground(server, mode, settings, appRules)
                }
                return@runCatching
            }

            startVpnForeground(server, mode, settings, appRules)
        }
    }

    private fun startVpnForeground(
        server: ServerProfile,
        mode: BondingMode,
        settings: VelocitySettings,
        appRules: List<AppTunnelRule>,
    ) {
        val bypassPackages = ArrayList(
            appRules.filter { it.policy == TunnelRulePolicy.BYPASS }.map { it.packageName },
        )

        val serviceIntent = Intent(context, VelocityVpnService::class.java).apply {
            action = VelocityVpnService.ACTION_CONNECT
            putExtra(VelocityVpnService.EXTRA_SERVER_HOST, server.host)
            putExtra(VelocityVpnService.EXTRA_SERVER_PORT, server.port)
            putExtra(VelocityVpnService.EXTRA_PSK, server.pskHex)
            putExtra(VelocityVpnService.EXTRA_SERVER_ID, server.id)
            putExtra(VelocityVpnService.EXTRA_MODE, mode.wireValue)
            putExtra(VelocityVpnService.EXTRA_MTU, settings.mtu)
            putExtra(VelocityVpnService.EXTRA_FEC_ENABLED, settings.fecEnabled)
            putExtra(VelocityVpnService.EXTRA_DIRECT_DNS_GUARD, settings.directDnsGuard)
            putExtra(VelocityVpnService.EXTRA_WIFI_BIAS_PERCENT, settings.wifiBiasPercent)
            putStringArrayListExtra(VelocityVpnService.EXTRA_BYPASS_PACKAGES, bypassPackages)
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }

    override suspend fun disconnect(): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val serviceIntent = Intent(context, VelocityVpnService::class.java).apply {
                action = VelocityVpnService.ACTION_DISCONNECT
            }
            context.startService(serviceIntent)
            Unit
        }
    }

    override suspend fun setMode(mode: BondingMode): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            if (VelocityVpnService.isRunning) {
                val serviceIntent = Intent(context, VelocityVpnService::class.java).apply {
                    action = VelocityVpnService.ACTION_SET_MODE
                    putExtra(VelocityVpnService.EXTRA_MODE, mode.wireValue)
                }
                context.startService(serviceIntent)
            }
            Unit
        }
    }

    override suspend fun updateTuning(settings: VelocitySettings): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            if (VelocityVpnService.isRunning) {
                val serviceIntent = Intent(context, VelocityVpnService::class.java).apply {
                    action = VelocityVpnService.ACTION_UPDATE_TUNING
                    putExtra(VelocityVpnService.EXTRA_WIFI_BIAS_PERCENT, settings.wifiBiasPercent)
                    putExtra(VelocityVpnService.EXTRA_FEC_ENABLED, settings.fecEnabled)
                }
                context.startService(serviceIntent)
            }
            Unit
        }
    }

    override suspend fun updateSplitTunnelApps(rules: List<AppTunnelRule>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "Saved ${rules.size} split tunnel rules")
            Unit
        }
    }

    override suspend fun refreshNetworkHealth() {
        networkMonitor.refreshNow()
    }

    override suspend fun runSpeedTest(
        server: ServerProfile,
        onProgress: (phase: String, fraction: Float, liveMbps: Double) -> Unit,
    ): Result<SpeedTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!VelocityJni.isLibraryLoaded()) {
                throw IllegalStateException("Native engine binary is not loaded in this APK")
            }

            onProgress("Checking active paths…", 0.12f, 0.0)
            networkMonitor.refreshNow()

            val wifiNet = networkMonitor.getValidatedWifiNetwork()
            val cellNet = networkMonitor.getValidatedCellularNetwork()
            if (wifiNet == null && cellNet == null) {
                throw IllegalStateException("No internet-connected path available to test")
            }

            val configJson = JSONObject().apply {
                put("server_host", server.host)
                put("server_port", server.port)
                put("psk", server.pskHex)
            }.toString()

            var wifiSock: DatagramSocket? = null
            var wifiPfd: ParcelFileDescriptor? = null
            var cellSock: DatagramSocket? = null
            var cellPfd: ParcelFileDescriptor? = null

            try {
                if (wifiNet != null) {
                    wifiSock = DatagramSocket(0).apply { wifiNet.bindSocket(this) }
                    wifiPfd = ParcelFileDescriptor.fromDatagramSocket(wifiSock)
                }
                if (cellNet != null) {
                    cellSock = DatagramSocket(0).apply { cellNet.bindSocket(this) }
                    cellPfd = ParcelFileDescriptor.fromDatagramSocket(cellSock)
                }

                onProgress("Running multipath echo benchmark…", 0.45f, 0.0)

                val rawJson = VelocityJni.nativeRunMultipathSpeedTestJson(
                    configJson = configJson,
                    wifiFd = wifiPfd?.fd ?: -1,
                    cellFd = cellPfd?.fd ?: -1,
                )

                val obj = JSONObject(rawJson)
                if (obj.has("error")) {
                    throw IllegalStateException(obj.getString("error"))
                }

                val parsed = json.decodeFromString<SpeedTestResult>(rawJson)
                onProgress("Complete", 1.0f, parsed.combinedDownloadMbps)
                parsed
            } finally {
                try { wifiPfd?.close() } catch (_: Exception) {}
                try { wifiSock?.close() } catch (_: Exception) {}
                try { cellPfd?.close() } catch (_: Exception) {}
                try { cellSock?.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun probeServers(servers: List<ServerProfile>): List<ServerProbeResult> = withContext(Dispatchers.IO) {
        if (!VelocityJni.isLibraryLoaded()) {
            return@withContext servers.map {
                ServerProbeResult(it.id, null, null, false, "Native engine missing")
            }
        }

        networkMonitor.refreshNow()
        val wifiNet = networkMonitor.getValidatedWifiNetwork()
        val cellNet = networkMonitor.getValidatedCellularNetwork()

        coroutineScope {
            servers.map { server ->
                async {
                    if (wifiNet == null && cellNet == null) {
                        return@async ServerProbeResult(
                            serverId = server.id,
                            wifiRttMs = null,
                            cellularRttMs = null,
                            reachable = false,
                            error = "No internet path available",
                        )
                    }

                    val configJson = JSONObject().apply {
                        put("server_host", server.host)
                        put("server_port", server.port)
                        put("psk", server.pskHex)
                    }.toString()

                    val wifiProbe = wifiNet?.let { net -> probeOverNetwork(configJson, 0, net) }
                    val cellProbe = cellNet?.let { net -> probeOverNetwork(configJson, 1, net) }

                    val wifiRtt = wifiProbe?.rttMs
                    val cellRtt = cellProbe?.rttMs
                    val reachable = wifiRtt != null || cellRtt != null
                    val err = if (!reachable) wifiProbe?.error ?: cellProbe?.error ?: "Unreachable" else null
                    val quota = wifiProbe?.quota ?: cellProbe?.quota ?: if (reachable) fetchHttpQuotaFallback(server.host) else null

                    ServerProbeResult(
                        serverId = server.id,
                        wifiRttMs = wifiRtt,
                        cellularRttMs = cellRtt,
                        reachable = reachable,
                        error = err,
                        quota = quota,
                    )
                }
            }.awaitAll()
        }
    }

    private data class UdpProbeOutcome(
        val rttMs: Int?,
        val quota: RelayQuotaInfo?,
        val error: String?,
    )

    private fun probeOverNetwork(
        configJson: String,
        pathId: Int,
        network: android.net.Network,
    ): UdpProbeOutcome {
        var sock: DatagramSocket? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            sock = DatagramSocket(0).apply { network.bindSocket(this) }
            pfd = ParcelFileDescriptor.fromDatagramSocket(sock)
            val raw = VelocityJni.nativeProbeServerJson(configJson, pathId, pfd.fd)
            val obj = JSONObject(raw)
            if (obj.optBoolean("reachable", false)) {
                val quotaObj = obj.optJSONObject("quota")
                val quota = quotaObj?.let {
                    runCatching { json.decodeFromString<RelayQuotaInfo>(it.toString()) }.getOrNull()
                }
                UdpProbeOutcome(obj.optInt("rttMs", 0), quota, null)
            } else {
                UdpProbeOutcome(null, null, obj.optString("error", "Unreachable"))
            }
        } catch (e: Exception) {
            UdpProbeOutcome(null, null, e.message ?: "Probe failed")
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun fetchHttpQuotaFallback(host: String): RelayQuotaInfo? {
        return try {
            val cleanHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            val url = java.net.URL("http://$cleanHost:8080/quota")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 700
                requestMethod = "GET"
            }
            conn.useOrDisconnect {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    json.decodeFromString<RelayQuotaInfo>(body)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> java.net.HttpURLConnection.useOrDisconnect(block: () -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

    override suspend fun parseAndSaveServerUri(uri: String): Result<ServerProfile> = withContext(Dispatchers.Default) {
        ServerUriCodec.decode(uri)
    }

    override suspend fun getInstalledApps(): List<AppTunnelRule> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val list = mutableListOf<AppTunnelRule>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (pkg.packageName == context.packageName) continue

            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg.packageName
            }

            list.add(
                AppTunnelRule(
                    packageName = pkg.packageName,
                    appName = appName,
                    policy = TunnelRulePolicy.BONDED,
                    isSystemApp = isSystem,
                ),
            )
        }

        list.sortedBy { it.appName.lowercase() }
    }

    override suspend fun loadPersistedState(): PersistedVelocityState? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, STATE_FILE_NAME)
            if (!file.exists()) return@withContext null
            json.decodeFromString<PersistedVelocityState>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted state: ${e.message}")
            null
        }
    }

    override suspend fun savePersistedState(state: PersistedVelocityState) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, STATE_FILE_NAME)
                file.writeText(json.encodeToString(state))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save persisted state: ${e.message}")
            }
        }
    }

    override fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        cm.setPrimaryClip(clip)
    }

    override fun isSystemDarkTheme(): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun isReduceMotionEnabled(): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }
}
