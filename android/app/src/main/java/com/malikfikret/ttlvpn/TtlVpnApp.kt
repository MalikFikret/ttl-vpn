package com.malikfikret.ttlvpn

import android.app.Application

class TtlVpnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // If the process died without the service publishing (e.g. Force stop), placed
        // widgets still show the last state, maybe "Connected". Any of our components
        // starting (app, tile, widget, service) runs this first, so one redraw with the
        // fresh process's state self-corrects them. No polling needed.
        TtlWidgetProvider.updateAll(this)
    }
}
