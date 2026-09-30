package com.malikfikret.ttlvpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TtlTileService : TileService() {

    companion object {
        // Asks the system to show its "Add tile" dialog (Android 13+). The callback runs
        // on the main thread with a StatusBarManager.TILE_ADD_REQUEST_* code.
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun requestAdd(context: Context, onResult: (Int) -> Unit) {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, TtlTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_stamp),
                ContextCompat.getMainExecutor(context)
            ) { result -> onResult(result) }
        }
    }

    private val scope = MainScope()
    private var listening: Job? = null

    override fun onTileAdded() {
        AppSettings.setQsTileAdded(this, true)
    }

    override fun onTileRemoved() {
        AppSettings.setQsTileAdded(this, false)
    }

    // Called while the tile is visible (shade open). Following the state stream here
    // keeps the tile in sync with changes made anywhere else: the app, the notification's
    // Stop, a revoke. Nothing runs while the shade is closed.
    override fun onStartListening() {
        listening?.cancel()
        listening = scope.launch {
            VpnStateRepository.state.collect(::render)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (VpnController.isActive()) {
            // Turning it off while locked would silently send traffic out with the
            // default TTL, billed to the wrong package, so stopping requires unlock.
            // Turning it on is harmless and works on the lock screen.
            if (isLocked) unlockAndRun { VpnController.stop(this) } else VpnController.stop(this)
            return
        }
        when (VpnController.start(this)) {
            StartResult.Started -> Unit
            // Consent needs an Activity; a refused start can be retried from the app.
            is StartResult.NeedsConsent, StartResult.NotAllowed -> openApp()
        }
    }

    private fun openApp() {
        val launch = { startAppAndCollapse() }
        if (isLocked) unlockAndRun(launch) else launch()
    }

    // Lint can't see that the deprecated overload only runs below Android 14.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startAppAndCollapse() {
        val intent = VpnController.openAppIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            // The Intent overload is deprecated (and throws when targeting 34+ on 14+),
            // but it's the only one that exists below Android 14.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = if (VpnController.isActive(state)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // The app's chosen language, which on Android 7-12 can differ from this service's.
        val res = AppLanguages.localized(this)
        val status = when (state) {
            is VpnState.Connected -> res.getString(R.string.tile_subtitle_connected, formatTtl(state.ttl))
            VpnState.Connecting -> res.getString(R.string.tile_subtitle_connecting)
            VpnState.Disconnected -> res.getString(R.string.tile_subtitle_off)
            is VpnState.Error -> res.getString(R.string.tile_subtitle_error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = status
        tile.updateTile()
    }
}
