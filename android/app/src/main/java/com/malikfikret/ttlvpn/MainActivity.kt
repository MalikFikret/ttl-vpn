package com.malikfikret.ttlvpn

import android.Manifest
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malikfikret.ttlvpn.ui.HomeScreen
import com.malikfikret.ttlvpn.ui.SettingsScreen
import com.malikfikret.ttlvpn.ui.rememberReducedMotion
import com.malikfikret.ttlvpn.ui.theme.BackgroundDark
import com.malikfikret.ttlvpn.ui.theme.BackgroundLight
import com.malikfikret.ttlvpn.ui.theme.TTLVPNTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

// Two screens don't justify a navigation library; an enum is Bundle-saveable as is.
private enum class Screen { Home, Settings }

// Same values as enableEdgeToEdge()'s defaults for 3-button navigation bars.
private val NAV_SCRIM_LIGHT = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val NAV_SCRIM_DARK = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

class MainActivity : ComponentActivity() {

    // Android 13+: needed for the "VPN active" notification to be visible.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 12+: the system applied the persisted per-app night mode before we even
        // started, so the configuration is already right and no read is needed. Older
        // versions have no such mechanism, so read the choice now, before the first frame.
        val initialTheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null else readThemeModeBlocking()
        applySystemAppearance(isDark(initialTheme))
        requestNotificationPermissionIfNeeded()
        setContent {
            // null (Android 12+ only) until DataStore's first read: follow the
            // configuration meanwhile, and don't push the placeholder to the system.
            val themeMode by remember { AppSettings.themeMode(applicationContext) }
                .collectAsStateWithLifecycle(initialValue = initialTheme)
            val dark = when (themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System, null -> isSystemInDarkTheme()
            }
            LaunchedEffect(themeMode) { themeMode?.let(::persistNightMode) }
            LaunchedEffect(dark) { applySystemAppearance(dark) }

            TTLVPNTheme(darkTheme = dark) {
                val serviceState by VpnStateRepository.state.collectAsStateWithLifecycle()
                // null until DataStore's first read; the UI shows a placeholder meanwhile.
                val configuredTtl by remember { AppSettings.ttl(applicationContext) }
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
                            onTtlClick = { screen = Screen.Settings },
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
                            themeMode = themeMode ?: ThemeMode.System,
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

    private fun isDark(mode: ThemeMode?): Boolean = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System, null ->
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    // Pre-Android 12 only. A small main-thread disk read (normally a few ms), bounded so
    // a slow DataStore can never stall launch; falls back to System.
    private fun readThemeModeBlocking(): ThemeMode {
        val mode = try {
            runBlocking { withTimeoutOrNull(200) { AppSettings.themeMode(applicationContext).first() } }
        } catch (e: Exception) {
            null
        }
        return mode ?: ThemeMode.System
    }

    // Android 12+: the system persists the choice and applies it at process start (to the
    // splash, window and configuration), which is what makes launch flash-free. Setting
    // the value it already has is a no-op; a real change recreates the activity.
    private fun persistNightMode(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (mode) {
                ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
            }
        )
    }

    private fun applySystemAppearance(dark: Boolean) {
        // The default enableEdgeToEdge() picks bar icon colors from the *system* theme,
        // which gives dark icons on a dark app when the in-app choice overrides it.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark }
        )
        // Before Android 12 the XML window background follows the system theme; match
        // it to the app theme so nothing of the wrong color shows around the content.
        window.setBackgroundDrawable(
            (if (dark) BackgroundDark else BackgroundLight).toArgb().toDrawable()
        )
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
