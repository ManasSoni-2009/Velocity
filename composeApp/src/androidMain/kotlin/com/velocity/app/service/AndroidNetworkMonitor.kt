package com.velocity.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.velocity.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on Android network intelligence engine.
 * Monitors Wi-Fi and Cellular independently of VPN state, checks Android validation + captive portal
 * capabilities, and performs lightweight per-network bound DNS reachability probes to immediately
 * distinguish "Wi-Fi connected with no internet" from "Wi-Fi online".
 */
class AndroidNetworkMonitor private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "AndroidNetworkMonitor"

        @Volatile
        private var instance: AndroidNetworkMonitor? = null

        fun getInstance(context: Context): AndroidNetworkMonitor =
            instance ?: synchronized(this) {
                instance ?: AndroidNetworkMonitor(context.applicationContext).also { instance = it }
            }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val started = AtomicBoolean(false)

    @Volatile
    var protectSocketHook: ((DatagramSocket) -> Boolean)? = null

    @Volatile
    private var wifiNetwork: Network? = null

    @Volatile
    private var wifiCaps: NetworkCapabilities? = null

    @Volatile
    private var wifiActiveInternetOk: Boolean? = null

    @Volatile
    private var cellularNetwork: Network? = null

    @Volatile
    private var cellularCaps: NetworkCapabilities? = null

    @Volatile
    private var cellularActiveInternetOk: Boolean? = null

    private val _healthFlow = MutableStateFlow(NetworkHealthSnapshot())
    val healthFlow: StateFlow<NetworkHealthSnapshot> = _healthFlow.asStateFlow()

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerCallbacks()
        scope.launch {
            refreshNow()
        }
    }

    fun getValidatedWifiNetwork(): Network? {
        val net = wifiNetwork ?: return null
        val caps = wifiCaps ?: connectivityManager.getNetworkCapabilities(net) ?: return null
        return if (isNetworkInternetReady(caps, wifiActiveInternetOk)) net else null
    }

    fun getValidatedCellularNetwork(): Network? {
        val net = cellularNetwork ?: return null
        val caps = cellularCaps ?: connectivityManager.getNetworkCapabilities(net) ?: return null
        return if (isNetworkInternetReady(caps, cellularActiveInternetOk)) net else null
    }

    suspend fun refreshNow() = withContext(Dispatchers.IO) {
        snapshotActivePhysicalNetworks()
        wifiNetwork?.let { net ->
            wifiActiveInternetOk = probeNetworkDnsInternet(net)
        } ?: run {
            wifiActiveInternetOk = null
        }
        cellularNetwork?.let { net ->
            cellularActiveInternetOk = probeNetworkDnsInternet(net)
        } ?: run {
            cellularActiveInternetOk = null
        }
        recomputeAndPublish()
    }

    private fun registerCallbacks() {
        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cellReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                wifiCaps = connectivityManager.getNetworkCapabilities(network)
                wifiActiveInternetOk = null
                recomputeAndPublish()
                scope.launch {
                    wifiActiveInternetOk = probeNetworkDnsInternet(network)
                    recomputeAndPublish()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (wifiNetwork == null || wifiNetwork == network) {
                    wifiNetwork = network
                    wifiCaps = caps
                    recomputeAndPublish()
                    scope.launch {
                        wifiActiveInternetOk = probeNetworkDnsInternet(network)
                        recomputeAndPublish()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (wifiNetwork == network) {
                    wifiNetwork = null
                    wifiCaps = null
                    wifiActiveInternetOk = null
                    recomputeAndPublish()
                }
            }
        }

        cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                cellularCaps = connectivityManager.getNetworkCapabilities(network)
                cellularActiveInternetOk = null
                recomputeAndPublish()
                scope.launch {
                    cellularActiveInternetOk = probeNetworkDnsInternet(network)
                    recomputeAndPublish()
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (cellularNetwork == null || cellularNetwork == network) {
                    cellularNetwork = network
                    cellularCaps = caps
                    recomputeAndPublish()
                    scope.launch {
                        cellularActiveInternetOk = probeNetworkDnsInternet(network)
                        recomputeAndPublish()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (cellularNetwork == network) {
                    cellularNetwork = null
                    cellularCaps = null
                    cellularActiveInternetOk = null
                    recomputeAndPublish()
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(wifiReq, wifiCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Wi-Fi callback: ${e.message}")
        }
        try {
            connectivityManager.registerNetworkCallback(cellReq, cellularCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Cellular callback: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun snapshotActivePhysicalNetworks() {
        var foundWifi: Network? = null
        var foundWifiCaps: NetworkCapabilities? = null
        var foundCell: Network? = null
        var foundCellCaps: NetworkCapabilities? = null

        for (net in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && foundWifi == null) {
                foundWifi = net
                foundWifiCaps = caps
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && foundCell == null) {
                foundCell = net
                foundCellCaps = caps
            }
        }

        wifiNetwork = foundWifi
        wifiCaps = foundWifiCaps
        cellularNetwork = foundCell
        cellularCaps = foundCellCaps
    }

    /**
     * Performs a lightweight DNS A-query over a socket explicitly bound to `network`.
     * Immediately catches Wi-Fi networks with a dead router WAN or captive blackhole.
     */
    private fun probeNetworkDnsInternet(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false

        return try {
            DatagramSocket().use { sock ->
                protectSocketHook?.invoke(sock)
                network.bindSocket(sock)
                sock.soTimeout = 1100

                // Minimal DNS query for example.com (ID=0x4242, QDCOUNT=1)
                val query = byteArrayOf(
                    0x42, 0x42, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x07,
                    'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
                    'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                    0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                    0x00, 0x00, 0x01, 0x00, 0x01,
                )
                val dest = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
                sock.send(DatagramPacket(query, query.size, dest, 53))
                val resp = ByteArray(512)
                val pkt = DatagramPacket(resp, resp.size)
                sock.receive(pkt)
                pkt.length >= 12 && resp[0] == 0x42.toByte() && resp[1] == 0x42.toByte()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Active DNS probe failed on $network: ${e.message}")
            false
        }
    }

    private fun isNetworkInternetReady(caps: NetworkCapabilities, probeOk: Boolean?): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            return false
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return when (probeOk) {
            true -> true
            false -> false
            null -> validated
        }
    }

    private fun recomputeAndPublish() {
        val wifiHealth = evaluateWifiHealth()
        val cellHealth = evaluateCellularHealth()
        val snapshot = summarizeHealth(wifiHealth, cellHealth)
        _healthFlow.value = snapshot
        VpnStateHolder.updateNetworkHealth(snapshot)
    }

    @Suppress("DEPRECATION")
    private fun evaluateWifiHealth(): LinkHealth {
        val isWifiEnabled = wifiManager?.isWifiEnabled ?: true
        val net = wifiNetwork
        val caps = wifiCaps ?: net?.let { connectivityManager.getNetworkCapabilities(it) }

        if (!isWifiEnabled || net == null || caps == null) {
            return LinkHealth(
                type = LinkType.WIFI,
                readiness = LinkReadiness.UNAVAILABLE,
                networkLabel = "Wi-Fi",
                detailText = if (!isWifiEnabled) "Wi-Fi is off" else "Not connected",
                isMetered = false,
                signalBars = 0,
            )
        }

        val rssi = extractWifiRssi(caps)
        val linkSpeed = extractWifiLinkSpeed(caps)
        val bars = rssiToBars(rssi)
        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val hasInternetCap = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val probe = wifiActiveInternetOk

        val readiness = when {
            isCaptive -> LinkReadiness.CAPTIVE_PORTAL
            !hasInternetCap -> LinkReadiness.NO_INTERNET
            probe == false -> LinkReadiness.NO_INTERNET
            isValidated && probe != false -> LinkReadiness.ONLINE
            probe == null -> LinkReadiness.CHECKING
            else -> LinkReadiness.ONLINE
        }

        val detail = when (readiness) {
            LinkReadiness.CAPTIVE_PORTAL -> "Sign in needed"
            LinkReadiness.NO_INTERNET -> "No internet"
            LinkReadiness.CHECKING -> "Checking…"
            LinkReadiness.ONLINE -> "Internet ready"
            LinkReadiness.BONDED -> "Active in bond"
            LinkReadiness.CONNECTING -> "Connecting…"
            LinkReadiness.UNAVAILABLE -> "Not connected"
        }

        return LinkHealth(
            type = LinkType.WIFI,
            readiness = readiness,
            networkLabel = "Wi-Fi",
            detailText = detail,
            isMetered = isMetered,
            signalBars = bars,
            signalDbm = rssi,
            linkSpeedMbps = linkSpeed,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun evaluateCellularHealth(): LinkHealth {
        val airplaneOn = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Exception) {
            false
        }
        if (airplaneOn) {
            return LinkHealth(
                type = LinkType.CELLULAR,
                readiness = LinkReadiness.UNAVAILABLE,
                networkLabel = "Cellular",
                detailText = "Airplane mode",
                isMetered = true,
            )
        }

        val simReady = telephonyManager?.simState == TelephonyManager.SIM_STATE_READY
        val dataEnabled = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager?.isDataEnabled ?: false
            } else {
                true
            }
        } catch (_: SecurityException) {
            cellularNetwork != null
        }

        val radioLabel = resolveCellularRadioLabel()
        val net = cellularNetwork
        val caps = cellularCaps ?: net?.let { connectivityManager.getNetworkCapabilities(it) }

        if (net != null && caps != null) {
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
            ) {
                caps.signalStrength
            } else {
                -80
            }

            val readiness = when {
                isCaptive -> LinkReadiness.CAPTIVE_PORTAL
                !hasInternet -> LinkReadiness.NO_INTERNET
                cellularActiveInternetOk == false -> LinkReadiness.NO_INTERNET
                isValidated && cellularActiveInternetOk != false -> LinkReadiness.ONLINE
                cellularActiveInternetOk == null -> LinkReadiness.CHECKING
                else -> LinkReadiness.ONLINE
            }

            val detail = when (readiness) {
                LinkReadiness.ONLINE -> "Internet ready"
                LinkReadiness.NO_INTERNET -> "No internet"
                LinkReadiness.CAPTIVE_PORTAL -> "Sign in needed"
                LinkReadiness.CHECKING -> "Checking…"
                else -> readiness.label
            }

            return LinkHealth(
                type = LinkType.CELLULAR,
                readiness = readiness,
                networkLabel = radioLabel,
                detailText = detail,
                isMetered = true,
                signalBars = rssiToBars(rssi),
                signalDbm = rssi,
            )
        }

        // Cellular radio is enabled on standby while Wi-Fi handles default traffic
        if (simReady && dataEnabled) {
            return LinkHealth(
                type = LinkType.CELLULAR,
                readiness = LinkReadiness.ONLINE,
                networkLabel = radioLabel,
                detailText = "Standby · Ready to bond",
                isMetered = true,
                signalBars = 3,
            )
        }

        val reason = when {
            !simReady -> "No SIM"
            !dataEnabled -> "Mobile data off"
            else -> "Unavailable"
        }
        return LinkHealth(
            type = LinkType.CELLULAR,
            readiness = LinkReadiness.UNAVAILABLE,
            networkLabel = "Cellular",
            detailText = reason,
            isMetered = true,
        )
    }

    private fun summarizeHealth(wifi: LinkHealth, cell: LinkHealth): NetworkHealthSnapshot {
        val headline = when {
            wifi.readiness.hasInternet && cell.readiness.hasInternet ->
                "Wi-Fi & ${cell.networkLabel} ready"
            wifi.readiness == LinkReadiness.CAPTIVE_PORTAL && cell.readiness.hasInternet ->
                "Wi-Fi needs sign-in · ${cell.networkLabel} ready"
            wifi.readiness == LinkReadiness.NO_INTERNET && cell.readiness.hasInternet ->
                "Wi-Fi has no internet · ${cell.networkLabel} ready"
            wifi.readiness.hasInternet && !cell.readiness.hasInternet ->
                "Wi-Fi ready · ${cell.networkLabel} unavailable"
            !wifi.readiness.hasInternet && cell.readiness.hasInternet ->
                "${cell.networkLabel} ready · Wi-Fi off"
            wifi.readiness == LinkReadiness.CHECKING || cell.readiness == LinkReadiness.CHECKING ->
                "Checking connections…"
            else ->
                "No internet connection"
        }

        val advisory = when {
            wifi.readiness == LinkReadiness.CAPTIVE_PORTAL && cell.readiness.hasInternet ->
                "This Wi-Fi requires browser login. Velocity will exclude Wi-Fi and use ${cell.networkLabel} automatically."
            wifi.readiness == LinkReadiness.NO_INTERNET && cell.readiness.hasInternet ->
                "Wi-Fi is connected without internet access. Velocity will route over ${cell.networkLabel} until Wi-Fi internet returns."
            wifi.readiness == LinkReadiness.NO_INTERNET && !cell.readiness.hasInternet ->
                "Wi-Fi has no internet access and mobile data is unavailable."
            !wifi.readiness.hasInternet && !cell.readiness.hasInternet ->
                "Connect to Wi-Fi with internet or enable mobile data to bond."
            else -> null
        }

        return NetworkHealthSnapshot(
            wifi = wifi,
            cellular = cell,
            summaryHeadline = headline,
            advisoryMessage = advisory,
            lastUpdatedMs = System.currentTimeMillis(),
        )
    }

    @Suppress("DEPRECATION")
    private fun extractWifiRssi(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transport = caps.transportInfo as? WifiInfo
            if (transport != null && transport.rssi > -120) return transport.rssi
            if (caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                return caps.signalStrength
            }
        }
        return wifiManager?.connectionInfo?.rssi ?: -60
    }

    @Suppress("DEPRECATION")
    private fun extractWifiLinkSpeed(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val transport = caps.transportInfo as? WifiInfo
            if (transport != null && transport.linkSpeed > 0) return transport.linkSpeed
        }
        val kbps = caps.linkDownstreamBandwidthKbps
        return if (kbps > 0) kbps / 1000 else (wifiManager?.connectionInfo?.linkSpeed ?: 0)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun resolveCellularRadioLabel(): String {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted || telephonyManager == null) return "Cellular"

        return try {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_UMTS,
                -> "3G"
                else -> "Cellular"
            }
        } catch (_: SecurityException) {
            "Cellular"
        }
    }

    private fun rssiToBars(dbm: Int): Int = when {
        dbm >= -60 -> 4
        dbm >= -72 -> 3
        dbm >= -85 -> 2
        dbm > -110 -> 1
        else -> 0
    }
}
