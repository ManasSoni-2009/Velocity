package com.velocity.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import com.velocity.app.nativebridge.VelocityJni
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages concurrent Wi-Fi (Path 0) and Cellular (Path 1) sockets during an active VPN bond.
 * Requests both physical transports, verifies genuine internet validation (rejecting no-internet
 * or captive-portal networks automatically), protects sockets from TUN loopback, and binds them
 * to their physical Android Network before registering with the Rust core.
 */
class MultiNetworkManager(
    private val context: Context,
    private val protectSocketFn: (DatagramSocket) -> Boolean,
) {
    companion object {
        private const val TAG = "MultiNetworkManager"
        const val PATH_WIFI = 0
        const val PATH_CELLULAR = 1
        private const val METRICS_INTERVAL_MS = 1000L
        private const val HEALTH_PROBE_INTERVAL_MS = 3000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var tunnelHandle: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var metricsJob: Job? = null
    private var healthJob: Job? = null

    data class ActivePath(
        val pathId: Int,
        val network: Network,
        val socket: DatagramSocket,
        val pfd: ParcelFileDescriptor,
        val linkName: String,
        val isMetered: Boolean,
    )

    private val activePaths = ConcurrentHashMap<Int, ActivePath>()
    private val candidateNetworks = ConcurrentHashMap<Int, Network>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    fun start(handle: Long) {
        this.tunnelHandle = handle
        Log.i(TAG, "Starting MultiNetworkManager for tunnel handle=$tunnelHandle")

        registerWifiNetwork()
        registerCellularNetwork()
        startPeriodicMetrics()
        startPeriodicInternetValidation()
    }

    fun stop() {
        Log.i(TAG, "Stopping MultiNetworkManager")
        metricsJob?.cancel()
        metricsJob = null
        healthJob?.cancel()
        healthJob = null

        try {
            wifiCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering Wi-Fi callback: ${e.message}")
        }
        wifiCallback = null

        try {
            cellularCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering Cellular callback: ${e.message}")
        }
        cellularCallback = null

        activePaths.forEach { (pathId, path) ->
            closeActivePath(pathId, path)
        }
        activePaths.clear()
        candidateNetworks.clear()
        tunnelHandle = 0L
        scope.cancel()
    }

    private fun registerWifiNetwork() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                candidateNetworks[PATH_WIFI] = network
                scope.launch {
                    evaluateAndSyncPath(PATH_WIFI, network, "wlan0", isMetered = false)
                }
            }

            override fun onLost(network: Network) {
                if (candidateNetworks[PATH_WIFI] == network) {
                    candidateNetworks.remove(PATH_WIFI)
                }
                scope.launch { unregisterPath(PATH_WIFI) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                candidateNetworks[PATH_WIFI] = network
                scope.launch {
                    evaluateAndSyncPath(PATH_WIFI, network, "wlan0", isMetered = false)
                }
            }
        }

        try {
            connectivityManager.requestNetwork(request, wifiCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Wi-Fi network: ${e.message}", e)
        }
    }

    private fun registerCellularNetwork() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                candidateNetworks[PATH_CELLULAR] = network
                scope.launch {
                    evaluateAndSyncPath(PATH_CELLULAR, network, "rmnet0", isMetered = true)
                }
            }

            override fun onLost(network: Network) {
                if (candidateNetworks[PATH_CELLULAR] == network) {
                    candidateNetworks.remove(PATH_CELLULAR)
                }
                scope.launch { unregisterPath(PATH_CELLULAR) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                candidateNetworks[PATH_CELLULAR] = network
                scope.launch {
                    evaluateAndSyncPath(PATH_CELLULAR, network, "rmnet0", isMetered = true)
                }
            }
        }

        try {
            connectivityManager.requestNetwork(request, cellularCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Cellular network: ${e.message}", e)
        }
    }

    private fun isNetworkEligible(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            return false
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!validated) {
            // Confirm via quick bound DNS probe in case Android validation flag is still catching up
            return probeProtectedDns(network)
        }
        return true
    }

    private fun probeProtectedDns(network: Network): Boolean {
        return try {
            DatagramSocket().use { sock ->
                if (!protectSocketFn(sock)) return false
                network.bindSocket(sock)
                sock.soTimeout = 1000
                val query = byteArrayOf(
                    0x21, 0x21, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x07,
                    'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                    'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                    0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                    0x00, 0x00, 0x01, 0x00, 0x01,
                )
                sock.send(DatagramPacket(query, query.size, InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)), 53))
                val resp = ByteArray(256)
                val pkt = DatagramPacket(resp, resp.size)
                sock.receive(pkt)
                pkt.length >= 12
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun evaluateAndSyncPath(
        pathId: Int,
        network: Network,
        linkName: String,
        isMetered: Boolean,
    ) {
        if (tunnelHandle == 0L) return
        val eligible = isNetworkEligible(network)
        val existing = activePaths[pathId]

        if (!eligible) {
            if (existing != null) {
                Log.w(TAG, "Path $pathId ($linkName) lost internet validation; removing from active bond")
                unregisterPath(pathId)
            }
            return
        }

        if (existing != null && existing.network == network) {
            updateLinkMetrics(pathId)
            return
        }

        bindAndRegisterPath(pathId, network, linkName, isMetered)
    }

    @Synchronized
    private fun bindAndRegisterPath(
        pathId: Int,
        network: Network,
        linkName: String,
        isMetered: Boolean,
    ) {
        if (tunnelHandle == 0L) return

        activePaths[pathId]?.let { closeActivePath(pathId, it) }

        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }

            if (!protectSocketFn(socket)) {
                Log.e(TAG, "Failed to protect socket for path=$pathId")
                socket.close()
                return
            }

            network.bindSocket(socket)

            val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
            val fd = pfd.fd

            val registered = VelocityJni.nativeRegisterSocket(
                handle = tunnelHandle,
                pathId = pathId,
                socketFd = fd,
                linkName = linkName,
                isMetered = isMetered,
            )

            if (registered) {
                val active = ActivePath(
                    pathId = pathId,
                    network = network,
                    socket = socket,
                    pfd = pfd,
                    linkName = linkName,
                    isMetered = isMetered,
                )
                activePaths[pathId] = active
                Log.i(TAG, "Registered validated path $pathId ($linkName) fd=$fd")
                updateLinkMetrics(pathId)
            } else {
                Log.e(TAG, "nativeRegisterSocket returned false for path $pathId")
                pfd.close()
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding socket for path=$pathId: ${e.message}", e)
        }
    }

    @Synchronized
    private fun unregisterPath(pathId: Int) {
        val path = activePaths.remove(pathId)
        if (path != null) {
            closeActivePath(pathId, path)
        }
    }

    private fun closeActivePath(pathId: Int, path: ActivePath) {
        Log.i(TAG, "Unregistering path $pathId from native core")
        if (tunnelHandle != 0L) {
            try {
                VelocityJni.nativeUnregisterSocket(tunnelHandle, pathId)
            } catch (e: Exception) {
                Log.w(TAG, "Error in nativeUnregisterSocket: ${e.message}")
            }
        }
        try {
            path.pfd.close()
        } catch (_: Exception) {}
        try {
            path.socket.close()
        } catch (_: Exception) {}
    }

    private fun startPeriodicMetrics() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive) {
                delay(METRICS_INTERVAL_MS)
                activePaths.keys.forEach { pathId ->
                    updateLinkMetrics(pathId)
                }
            }
        }
    }

    private fun startPeriodicInternetValidation() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (isActive) {
                delay(HEALTH_PROBE_INTERVAL_MS)
                candidateNetworks.forEach { (pathId, net) ->
                    val linkName = if (pathId == PATH_WIFI) "wlan0" else "rmnet0"
                    val isMetered = pathId == PATH_CELLULAR
                    evaluateAndSyncPath(pathId, net, linkName, isMetered)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateLinkMetrics(pathId: Int) {
        if (tunnelHandle == 0L) return
        val path = activePaths[pathId] ?: return

        try {
            var rssiDbm = -120
            var linkSpeedMbps = 0
            val caps = connectivityManager.getNetworkCapabilities(path.network)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                val transport = caps.transportInfo
                if (transport is WifiInfo && transport.rssi > -120) {
                    rssiDbm = transport.rssi
                    if (transport.linkSpeed > 0) linkSpeedMbps = transport.linkSpeed
                } else if (caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                    rssiDbm = caps.signalStrength
                }
            }
            if (linkSpeedMbps == 0 && caps != null && caps.linkDownstreamBandwidthKbps > 0) {
                linkSpeedMbps = caps.linkDownstreamBandwidthKbps / 1000
            }

            if (pathId == PATH_WIFI && rssiDbm == -120) {
                val info = wifiManager?.connectionInfo
                if (info != null) {
                    rssiDbm = info.rssi
                    if (linkSpeedMbps == 0 && info.linkSpeed > 0) {
                        linkSpeedMbps = info.linkSpeed
                    }
                }
            } else if (pathId == PATH_CELLULAR && rssiDbm == -120 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    telephonyManager?.signalStrength?.cellSignalStrengths?.firstOrNull()?.let {
                        rssiDbm = it.dbm
                    }
                } catch (_: SecurityException) {}
            }

            VelocityJni.nativeUpdateLinkMetrics(
                handle = tunnelHandle,
                pathId = pathId,
                rssiDbm = rssiDbm,
                linkSpeedMbps = linkSpeedMbps,
            )
        } catch (e: Exception) {
            Log.v(TAG, "Metrics update skipped: ${e.message}")
        }
    }
}
