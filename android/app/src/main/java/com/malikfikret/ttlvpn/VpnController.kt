package com.malikfikret.ttlvpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat

sealed interface StartResult {
    data object Started : StartResult
    // VpnService.prepare() returned a consent Intent; only an Activity can show it.
    data class NeedsConsent(val intent: Intent) : StartResult
    // The system refused the foreground-service start (Android 12+ background limits).
    data object NotAllowed : StartResult
}

// The one place the UI, the Quick Settings tile and the home screen widget turn the VPN
// on and off. It only sends intents; the service's single engine thread does the actual
// work and resolves races, so callers never need to coordinate with each other.
// Each caller decides what to do with a StartResult: the app shows the consent dialog,
// the tile and the widget open the app.
object VpnController {
    private const val TAG = "VpnController"

    fun isActive(state: VpnState = VpnStateRepository.state.value): Boolean =
        state is VpnState.Connecting || state is VpnState.Connected

    // Connecting also counts as on, so a tap during a start cancels it.
    // Returns null when it sent a stop.
    fun toggle(context: Context): StartResult? =
        if (isActive()) {
            stop(context)
            null
        } else {
            start(context)
        }

    fun start(context: Context): StartResult {
        VpnService.prepare(context)?.let { return StartResult.NeedsConsent(it) }
        return try {
            ContextCompat.startForegroundService(context, serviceIntent(context, TtlVpnService.ACTION_START))
            StartResult.Started
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+).
            Log.w(TAG, "Foreground service start refused", e)
            StartResult.NotAllowed
        }
    }

    fun stop(context: Context) {
        try {
            context.startService(serviceIntent(context, TtlVpnService.ACTION_STOP))
        } catch (e: IllegalStateException) {
            // Background start refused, which only happens when the service isn't running
            // (a running foreground service keeps the app startable): nothing to stop.
            Log.w(TAG, "Stop ignored; service not running", e)
        }
    }

    // Plain Stop then Start: the engine thread runs them in order, so the fresh start
    // re-reads the settings (e.g. a new TTL).
    fun reconnect(context: Context): StartResult {
        stop(context)
        return start(context)
    }

    // Same shape as the launcher intent, so it brings back the existing task instead of
    // stacking a second MainActivity on top of it.
    fun openAppIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun serviceIntent(context: Context, action: String) =
        Intent(context, TtlVpnService::class.java).setAction(action)
}
