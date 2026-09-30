package com.malikfikret.ttlvpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes

// Plain RemoteViews on purpose: no extra dependencies, and every update is one
// synchronous call, with nothing (like a Glance/WorkManager session) left running.
class TtlWidgetProvider : AppWidgetProvider() {

    // Placement, reboot, app update. No periodic updates (updatePeriodMillis="0").
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = VpnStateRepository.state.value
        appWidgetIds.forEach { draw(context, manager, it, state) }
    }

    // Resized: below Android 12 we choose the layout ourselves from the new width.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        draw(context, manager, appWidgetId, VpnStateRepository.state.value)
    }

    companion object {
        const val ACTION_TOGGLE = "com.malikfikret.ttlvpn.WIDGET_TOGGLE"

        // At or above this width (dp) the label fits next to the badge.
        private const val WIDE_MIN_WIDTH_DP = 100

        // Redraws every placed widget; a cheap no-op when none are placed. Called by the
        // service whenever it publishes a state, and once when the app process starts.
        fun updateAll(context: Context, state: VpnState = VpnStateRepository.state.value) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TtlWidgetProvider::class.java))
            ids.forEach { draw(context, manager, it, state) }
        }

        private fun draw(context: Context, manager: AppWidgetManager, appWidgetId: Int, state: VpnState) {
            // One binder call per draw; decides both the tap action and its description.
            val needsConsent = VpnService.prepare(context) != null
            val click = clickIntent(context, needsConsent)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ picks the largest layout that fits the current size.
                RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to build(context, R.layout.widget_small, state, needsConsent, click),
                        SizeF(WIDE_MIN_WIDTH_DP.toFloat(), 40f) to
                            build(context, R.layout.widget_wide, state, needsConsent, click)
                    )
                )
            } else {
                // 0 = no size reported yet: assume the default 2x1.
                val width = manager.getAppWidgetOptions(appWidgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val layout = if (width == 0 || width >= WIDE_MIN_WIDTH_DP) {
                    R.layout.widget_wide
                } else {
                    R.layout.widget_small
                }
                build(context, layout, state, needsConsent, click)
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun build(
            context: Context,
            @LayoutRes layout: Int,
            state: VpnState,
            needsConsent: Boolean,
            click: PendingIntent
        ): RemoteViews {
            val look = WidgetLook.of(context, state)
            return RemoteViews(context.packageName, layout).apply {
                setInt(R.id.widget_badge, "setBackgroundResource", look.badge)
                setImageViewResource(R.id.widget_icon, look.icon)
                if (layout == R.layout.widget_wide) setTextViewText(R.id.widget_label, look.label)
                setOnClickPendingIntent(android.R.id.background, click)
                setContentDescription(
                    android.R.id.background,
                    context.getString(
                        R.string.widget_content_description,
                        context.getString(R.string.app_name),
                        look.label,
                        context.getString(
                            when {
                                needsConsent -> R.string.widget_action_open_app
                                VpnController.isActive(state) -> R.string.widget_action_disconnect
                                else -> R.string.widget_action_connect
                            }
                        )
                    )
                )
            }
        }

        // When consent is known to be missing, the tap opens the app directly: an Activity
        // PendingIntent is always allowed to start, unlike an activity launched from a
        // broadcast receiver (restricted since Android 10, more so on 14+). Otherwise the
        // tap is an explicit broadcast to our non-exported receiver, which toggles.
        private fun clickIntent(context: Context, needsConsent: Boolean): PendingIntent =
            if (needsConsent) {
                PendingIntent.getActivity(
                    context,
                    0,
                    VpnController.openAppIntent(context),
                    PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, WidgetToggleReceiver::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE
                )
            }
    }
}

private data class WidgetLook(
    @param:DrawableRes val badge: Int,
    @param:DrawableRes val icon: Int,
    val label: String
) {
    companion object {
        fun of(context: Context, state: VpnState): WidgetLook =
            when (state) {
                VpnState.Disconnected -> WidgetLook(
                    R.drawable.widget_badge_off,
                    R.drawable.ic_widget_power_off,
                    context.getString(R.string.widget_state_off)
                )
                VpnState.Connecting -> WidgetLook(
                    R.drawable.widget_badge_connecting,
                    R.drawable.ic_widget_power_connecting,
                    context.getString(R.string.widget_state_connecting)
                )
                is VpnState.Connected -> WidgetLook(
                    R.drawable.widget_badge_connected,
                    R.drawable.ic_widget_power_connected,
                    context.getString(R.string.widget_state_connected, state.ttl)
                )
                is VpnState.Error -> WidgetLook(
                    R.drawable.widget_badge_error,
                    R.drawable.ic_widget_power_error,
                    context.getString(R.string.widget_state_error)
                )
            }
    }
}
