package com.malikfikret.ttlvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malikfikret.ttlvpn.ui.HomeScreen
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme

class MainActivity : ComponentActivity() {

    // A denied consent dialog never reaches the service, so the Activity shows it itself.
    // Transient on purpose: it's cleared by the next tap and not worth persisting.
    private var permissionDenied by mutableStateOf(false)

    // Shows the system "allow VPN" dialog, then starts the service if approved.
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService() else permissionDenied = true
        }

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
                val state = serviceState.let {
                    if (permissionDenied && (it is VpnState.Disconnected || it is VpnState.Error)) {
                        VpnState.Error(getString(R.string.error_permission_denied))
                    } else {
                        it
                    }
                }
                HomeScreen(state = state, onToggle = { onToggle(state) })
            }
        }
    }

    private fun onToggle(state: VpnState) {
        when (state) {
            // Stop also cancels a start in progress; the service handles that race.
            VpnState.Connecting, is VpnState.Connected -> stopVpnService()
            VpnState.Disconnected, is VpnState.Error -> onStartClicked()
        }
    }

    private fun onStartClicked() {
        permissionDenied = false
        // Returns an Intent if the user hasn't approved this app as a VPN yet; null otherwise.
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpnService()
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
