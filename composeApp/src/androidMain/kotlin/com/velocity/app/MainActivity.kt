package com.velocity.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.velocity.app.domain.BondingViewModel
import com.velocity.app.domain.ThemeMode
import com.velocity.app.platform.AndroidPlatformBridge

/**
 * Velocity's main Android activity.
 * Configures dynamic edge-to-edge status/navigation bar contrast, handles VpnService consent
 * continuation, runtime permissions, and velocity:// deep links.
 */
class MainActivity : ComponentActivity() {

    private var onVpnPermissionGranted: (() -> Unit)? = null
    private var viewModelRef: BondingViewModel? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            onVpnPermissionGranted?.invoke()
            onVpnPermissionGranted = null
        } else {
            onVpnPermissionGranted = null
            Toast.makeText(
                this,
                "VPN permission is needed to bond Wi-Fi and Cellular",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private lateinit var platformBridge: AndroidPlatformBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        requestNotificationPermissionIfNeeded()

        platformBridge = AndroidPlatformBridge(
            context = this,
            vpnPermissionLauncher = { onGranted ->
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    onVpnPermissionGranted = onGranted
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    onGranted()
                }
            },
        )

        setContent {
            val vm = remember(platformBridge) { BondingViewModel(platformBridge) }
            viewModelRef = vm
            val uiState by vm.uiState.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (uiState.settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            LaunchedEffect(isDark) {
                if (isDark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                }
            }

            LaunchedEffect(intent?.dataString) {
                val uri = intent?.dataString
                if (!uri.isNullOrBlank() && uri.startsWith("velocity://", ignoreCase = true)) {
                    vm.importServerUri(uri)
                }
            }

            VelocityApp(bridge = platformBridge, viewModel = vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.dataString
        if (!uri.isNullOrBlank() && uri.startsWith("velocity://", ignoreCase = true)) {
            viewModelRef?.importServerUri(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModelRef?.refreshNetworkHealth()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
