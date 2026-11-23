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
import android.telephony.CellSignalStrength
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import com.velocity.app.nativebridge.VelocityJni
import kotlinx.coroutines.*
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages concurrent Wi-Fi (Path 0) and Cellular (Path 1) sockets,
 * binding each to its respective physical network interface and registering
 * their file descriptors directly with the Velocity Rust multipath bonding core.
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

    data class ActivePath(
        val pathId: Int,
        val network: Network,
        val socket: DatagramSocket,
        val pfd: ParcelFileDescriptor,
        val linkName: String,
        val isMetered: Boolean,
    )

    private val activePaths = ConcurrentHashMap<Int, ActivePath>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    fun start(handle: Long) {
        this.tunnelHandle = handle
        Log.i(TAG, "Starting MultiNetworkManager for tunnel handle=$tunnelHandle")

        registerWifiNetwork()
        registerCellularNetwork()
        startPeriodicMetrics()
    }

    fun stop() {
        Log.i(TAG, "Stopping MultiNetworkManager")
        metricsJob?.cancel()
        metricsJob = null

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
                Log.i(TAG, "Wi-Fi network available: $network")
                scope.launch {
                    bindAndRegisterPath(
                        pathId = PATH_WIFI,
                        network = network,
                        linkName = "wlan0",
                        isMetered = false,
                    )
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi network lost: $network")
                scope.launch {
                    unregisterPath(PATH_WIFI)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                updateLinkMetrics(PATH_WIFI)
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
                Log.i(TAG, "Cellular network available: $network")
                scope.launch {
                    bindAndRegisterPath(
                        pathId = PATH_CELLULAR,
                        network = network,
                        linkName = "rmnet0",
                        isMetered = true,
                    )
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Cellular network lost: $network")
                scope.launch {
                    unregisterPath(PATH_CELLULAR)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                updateLinkMetrics(PATH_CELLULAR)
            }
        }

        try {
            connectivityManager.requestNetwork(request, cellularCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Cellular network: ${e.message}", e)
        }
    }

    @Synchronized
    private fun bindAndRegisterPath(
        pathId: Int,
        network: Network,
        linkName: String,
        isMetered: Boolean,
    ) {
        if (tunnelHandle == 0L) return

        // Unregister existing path if any
        activePaths[pathId]?.let { closeActivePath(pathId, it) }

        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }

            // Protect socket so its outgoing packets do not route through our own VPN TUN interface
            if (!protectSocketFn(socket)) {
                Log.e(TAG, "Failed to protect socket for path=$pathId")
                socket.close()
                return
            }

            // Bind physical socket to the specific Android Network (Wi-Fi or Cellular)
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
                Log.i(TAG, "Registered path $pathId ($linkName) fd=$fd with native core")
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
        } catch (ignored: Exception) {}
        try {
            path.socket.close()
        } catch (ignored: Exception) {}
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

    private fun updateLinkMetrics(pathId: Int) {
        if (tunnelHandle == 0L) return
        val path = activePaths[pathId] ?: return

        try {
            var rssiDbm = -120
            var linkSpeedMbps = 0

            if (pathId == PATH_WIFI) {
                val caps = connectivityManager.getNetworkCapabilities(path.network)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps?.signalStrength?.let { rssiDbm = it }
                }
                caps?.linkDownstreamBandwidthKbps?.let { linkSpeedMbps = it / 1000 }

                if (rssiDbm == -120) {
                    val info = wifiManager?.connectionInfo
                    if (info != null) {
                        rssiDbm = info.rssi
                        if (linkSpeedMbps == 0 && info.linkSpeed > 0) {
                            linkSpeedMbps = info.linkSpeed
                        }
                    }
                }
            } else if (pathId == PATH_CELLULAR) {
                val caps = connectivityManager.getNetworkCapabilities(path.network)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps?.signalStrength?.let { rssiDbm = it }
                }
                caps?.linkDownstreamBandwidthKbps?.let { linkSpeedMbps = it / 1000 }

                if (rssiDbm == -120 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val signal = telephonyManager?.signalStrength
                    signal?.cellSignalStrengths?.firstOrNull()?.let {
                        rssiDbm = it.dbm
                    }
                }
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
