package com.malikfikret.ttlvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

        // Every engine operation (start, stop, revoke, destroy) runs on this one thread,
        // strictly in submission order. It is process-wide rather than per instance, so a
        // new service instance's start can never overtake an old instance's final stop.
        private val engineDispatcher = Executors
            .newSingleThreadExecutor { Thread(it, "ttlvpn-engine") }
            .asCoroutineDispatcher()

        // Only read or written on engineDispatcher.
        private var engineRunning = false

        // The running session, kept so a repeated Start can report it again.
        // Only read or written on engineDispatcher.
        private var session: VpnState.Connected? = null

        // Bumped on the main thread by every start, stop and destroy request. A job whose
        // request is no longer the latest must not publish state or leave the foreground:
        // the newer request's job runs after it and owns the final outcome.
        private val latestRequest = AtomicLong()
    }

    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Set on the main thread by onRevoke, read on the main thread by onDestroy.
    private var revoked = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            enqueueStop(startId)
        } else {
            // Stays synchronous and before any engine work: the foreground-service start
            // deadline must never depend on IO.
            startAsForeground()
            enqueueStart(startId)
        }
        // Don't let the system restart us silently: the user starts the VPN explicitly.
        return START_NOT_STICKY
    }

    private fun isLatest(request: Long) = request == latestRequest.get()

    private fun enqueueStart(startId: Int) {
        val request = latestRequest.incrementAndGet()
        scope.launch { startVpn(request, startId) }
    }

    private fun enqueueStop(startId: Int?) {
        val request = latestRequest.incrementAndGet()
        scope.launch {
            stopEngine()
            if (isLatest(request)) {
                VpnStateRepository.update(VpnState.Disconnected)
                leaveForeground(request, startId)
            }
        }
    }

    // Runs on engineDispatcher. Deliberately has no suspension points: once it begins it
    // runs to completion, so cancellation can never land between detachFd() and start().
    private fun startVpn(request: Long, startId: Int) {
        // A newer request (double tap, Stop, destroy) is queued behind us and takes over.
        if (!isLatest(request)) return

        session?.let {
            VpnStateRepository.update(it)
            return
        }
        // Also clears any Error left by a previous attempt.
        VpnStateRepository.update(VpnState.Connecting)

        val tun: ParcelFileDescriptor = try {
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
                ?: return fail(request, startId, getString(R.string.error_permission_not_granted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build TUN interface", e)
            return fail(request, startId, getString(R.string.error_tun_failed, e.describe()))
        }

        // Taken before the engine starts, so the session counts all tunneled traffic.
        // All of it leaves through this app's own sockets.
        val uid = Process.myUid()
        val rxBaseline = TrafficStats.getUidRxBytes(uid)
        val txBaseline = TrafficStats.getUidTxBytes(uid)

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
            return fail(request, startId, getString(R.string.error_engine_failed, e.describe()))
        }
        val connected = VpnState.Connected(TTL, rxBaseline, txBaseline, SystemClock.elapsedRealtime())
        session = connected

        // If superseded, the engine stays up for the newer request's job to handle:
        // a Stop or destroy stops it, a repeated Start reports Connected.
        if (isLatest(request)) {
            VpnStateRepository.update(connected)
            showConnectedNotification(request)
        }
    }

    private fun Exception.describe() = message ?: getString(R.string.error_unknown)

    private fun fail(request: Long, startId: Int, message: String) {
        if (!isLatest(request)) return
        VpnStateRepository.update(VpnState.Error(message))
        leaveForeground(request, startId)
    }

    // Runs on engineDispatcher.
    private fun stopEngine() {
        if (engineRunning) {
            Ttlvpn.stop() // Also closes the TUN fd
            engineRunning = false
            session = null
            Log.i(TAG, "Engine stopped")
        }
    }

    // Hops to the main thread, where requests are counted, so the isLatest check can't race
    // with a Start arriving right now; otherwise we could remove that Start's foreground.
    private fun leaveForeground(request: Long, startId: Int?) {
        mainHandler.post {
            if (!isLatest(request)) return@post
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            // stopSelf(startId) is ignored if a newer start command has arrived since.
            if (startId != null) stopSelf(startId) else stopSelf()
        }
    }

    // Called when the user starts another VPN or revokes permission.
    override fun onRevoke() {
        revoked = true
        enqueueStop(startId = null)
        super.onRevoke() // Default implementation calls stopSelf()
    }

    override fun onDestroy() {
        // Supersede every queued or in-flight job, so none of them publishes state or
        // touches this dead service afterwards.
        latestRequest.incrementAndGet()
        // Queued jobs that haven't started never run. An in-flight start can't be
        // interrupted (it doesn't suspend), so it always finishes, fd handoff included.
        scope.cancel()
        // The final stop runs outside the cancelled scope, on the same thread, after any
        // in-flight start, so the engine is never left running and the fd never leaks.
        // The revoke message replaces the plain Disconnected that onRevoke's own stop job
        // would publish; that job is superseded here and usually never runs anyway.
        val revokedMessage = if (revoked) getString(R.string.error_revoked) else null
        CoroutineScope(engineDispatcher).launch {
            stopEngine()
            when {
                revokedMessage != null ->
                    VpnStateRepository.update(VpnState.Error(revokedMessage))
                // Keep an Error visible; it's why the service stopped.
                VpnStateRepository.state.value !is VpnState.Error ->
                    VpnStateRepository.update(VpnState.Disconnected)
            }
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification(getString(R.string.notification_connecting))
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

    // Posted on the main thread with the same isLatest check as leaveForeground: after a
    // Stop has removed the notification, a late update would bring it back as an orphan.
    private fun showConnectedNotification(request: Long) {
        val notification = buildNotification(getString(R.string.notification_connected, TTL))
        mainHandler.post {
            if (!isLatest(request)) return@post
            // Android 13+: without the permission the update is dropped anyway; checking
            // first keeps that explicit (and lint happy).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TtlVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE // Required on Android 12+, and safer
        )
        // Same flags as the launcher, so tapping brings back the existing task instead of
        // stacking a second MainActivity on top of it.
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Small icons must be a monochrome silhouette; the launcher layer is not.
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android 12+ may otherwise delay showing it by up to 10 seconds.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }
}
