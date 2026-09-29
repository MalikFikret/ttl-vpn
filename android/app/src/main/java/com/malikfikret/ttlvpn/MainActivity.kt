package com.malikfikret.ttlvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malikfikret.ttlvpn.ui.HomeScreen
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme

class MainActivity : ComponentActivity() {

    // Android 13+: needed for the "VPN active" notification to be visible.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            TTLVPNTheme {
                val serviceState by VpnStateRepository.state.collectAsStateWithLifecycle()

                // A denied consent dialog never reaches the service, so the UI keeps it.
                // Saveable, so it survives rotation; cleared by the next tap.
                var permissionDenied by rememberSaveable { mutableStateOf(false) }

                // Shows the system "allow VPN" dialog, then starts the service if approved.
                val vpnPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) startVpnService() else permissionDenied = true
                }

                val deniedMessage = stringResource(R.string.error_permission_denied)
                val state = if (permissionDenied &&
                    (serviceState is VpnState.Disconnected || serviceState is VpnState.Error)
                ) {
                    VpnState.Error(deniedMessage)
                } else {
                    serviceState
                }

                HomeScreen(
                    state = state,
                    onToggle = {
                        when (state) {
                            // Stop also cancels a start in progress; the service handles that race.
                            VpnState.Connecting, is VpnState.Connected -> stopVpnService()
                            VpnState.Disconnected, is VpnState.Error -> {
                                permissionDenied = false
                                // Returns an Intent if the user hasn't approved this app as a
                                // VPN yet; null otherwise.
                                val consent = VpnService.prepare(this)
                                if (consent != null) vpnPermission.launch(consent) else startVpnService()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, TtlVpnService::class.java)
            .setAction(TtlVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, TtlVpnService::class.java)
            .setAction(TtlVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
