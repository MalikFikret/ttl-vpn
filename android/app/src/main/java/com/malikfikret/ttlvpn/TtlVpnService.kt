package com.malikfikret.ttlvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ttlvpn.Ttlvpn

class TtlVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.malikfikret.ttlvpn.START"
        const val ACTION_STOP = "com.malikfikret.ttlvpn.STOP"

        private const val TAG = "TtlVpnService"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        private const val TTL = 63
    }

    private var engineRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
        } else {
            startVpn()
        }
        // Don't let the system restart us silently: the user starts the VPN explicitly.
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (engineRunning) return

        // Must happen quickly after start, or Android kills the service.
        startAsForeground()

        val tun: ParcelFileDescriptor? = try {
            Builder()
                .setSession("TTL VPN")
                .setMtu(MTU)
                // Private address for the virtual interface
                .addAddress("10.111.0.2", 32)
                // Capture ALL IPv4 traffic, so nothing leaks out with TTL 64
                .addRoute("0.0.0.0", 0)
                // IPv6 is intentionally not configured (no address, route, DNS server
                // or allowFamily). Android then blocks all IPv6 traffic, so apps fall
                // back to IPv4 and nothing leaks with hop limit 64. Advertising IPv6
                // made apps prefer it, but the engine can't reach IPv6 upstream on an
                // IPv4-only hotspot and reset those connections.
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                // Our own sockets (the engine's) must bypass the VPN to avoid a loop
                .addDisallowedApplication(packageName)
                .establish() // Returns null if VPN permission was not granted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build TUN interface", e)
            null
        }

        if (tun == null) {
            stopVpn()
            return
        }

        // Hand ownership of the fd to the Go engine; it closes it on stop.
        val fd = tun.detachFd()
        try {
            Ttlvpn.start(fd.toLong(), MTU.toLong(), TTL.toLong())
            engineRunning = true
            Log.i(TAG, "Engine started (fd=$fd, ttl=$TTL)")
        } catch (e: Exception) {
            Log.e(TAG, "Engine failed to start", e)
            // Engine never took the fd, so we must close it ourselves.
            ParcelFileDescriptor.adoptFd(fd).close()
            stopVpn()
        }
    }

    private fun stopEngine() {
        if (engineRunning) {
            Ttlvpn.stop() // Also closes the TUN fd
            engineRunning = false
            Log.i(TAG, "Engine stopped")
        }
    }

    private fun stopVpn() {
        stopEngine()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Called when the user starts another VPN or revokes permission.
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires declaring the foreground service type
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TtlVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE // Required on Android 12+, and safer
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TTL VPN is active")
            .setContentText("Outgoing TTL: $TTL")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}