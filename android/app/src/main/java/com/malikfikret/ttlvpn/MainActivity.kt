package com.malikfikret.ttlvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malikfikret.ttlvpn.ui.HomeScreen
import com.malikfikret.ttlvpn.ui.SettingsScreen
import com.malikfikret.ttlvpn.ui.rememberReducedMotion
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme

// Two screens don't justify a navigation library; an enum is Bundle-saveable as is.
private enum class Screen { Home, Settings }

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
                // null until DataStore's first read; the UI shows a placeholder meanwhile.
                val configuredTtl by remember { TtlSettings.ttl(applicationContext) }
                    .collectAsStateWithLifecycle(initialValue = null)
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                BackHandler(enabled = screen == Screen.Settings) { screen = Screen.Home }

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

                val onToggle = {
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

                val fadeMillis = if (rememberReducedMotion()) 0 else 220
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn(tween(fadeMillis)) togetherWith fadeOut(tween(fadeMillis)) },
                    label = "screen"
                ) { target ->
                    when (target) {
                        Screen.Home -> HomeScreen(
                            state = state,
                            configuredTtl = configuredTtl,
                            onToggle = onToggle,
                            topBarActions = {
                                IconButton(onClick = { screen = Screen.Settings }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings),
                                        contentDescription = stringResource(R.string.cd_open_settings)
                                    )
                                }
                            }
                        )
                        Screen.Settings -> SettingsScreen(
                            vpnState = serviceState,
                            savedTtl = configuredTtl,
                            onBack = { screen = Screen.Home },
                            // Plain Stop then Start: the service runs them in order on its
                            // engine thread, so the new TTL is read by the fresh start.
                            onReconnect = {
                                stopVpnService()
                                startVpnService()
                            }
                        )
                    }
                }
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
