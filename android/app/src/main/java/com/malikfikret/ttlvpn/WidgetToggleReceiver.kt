package com.malikfikret.ttlvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Not exported: only our own explicit, immutable PendingIntent from the widget can reach
// it. The widget provider itself must be exported (for system updates), so handling the
// toggle there would let any app turn the VPN on or off.
class WidgetToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TtlWidgetProvider.ACTION_TOGGLE) return

        // The service publishes the resulting state, which redraws the widget.
        when (VpnController.toggle(context)) {
            null, StartResult.Started -> Unit
            // Consent was lost after the widget was last drawn (or the start was refused).
            is StartResult.NeedsConsent, StartResult.NotAllowed -> {
                try {
                    context.startActivity(VpnController.openAppIntent(context))
                } catch (e: Exception) {
                    // Background activity starts are restricted and may be dropped or
                    // refused; the redraw below makes the next tap open the app directly.
                    Log.w("WidgetToggleReceiver", "Could not open the app", e)
                }
                TtlWidgetProvider.updateAll(context)
            }
        }
    }
}
