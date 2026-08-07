package com.example.selflearn.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * A real, working per-app firewall using Android's VpnService — the same approach used
 * by well-known no-root firewalls (e.g. NetGuard). No traffic-shaping magic needed:
 *
 *  - Apps you mark BLOCKED get their traffic routed into this local VPN tunnel.
 *  - This service simply reads those packets and never forwards them anywhere — they're
 *    silently dropped, so those apps lose network access entirely.
 *  - Apps you leave ALLOWED are excluded from the tunnel via addDisallowedApplication(),
 *    so their traffic goes straight to the internet as normal, completely unaffected.
 *
 * This means no complex packet-forwarding/NAT code is needed — the OS does the
 * "put this app's traffic through the tunnel or not" decision for us.
 */
class SimpleFirewallService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    @Volatile private var running = false

    companion object {
        const val PREFS_NAME = "firewall_prefs"
        const val KEY_BLOCKED_APPS = "blocked_apps"
        const val ACTION_STOP = "com.example.selflearn.firewall.STOP"
        private const val CHANNEL_ID = "firewall_channel"
        private const val NOTIF_ID = 42
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

        val builder = Builder()
            .setSession("SelfLearn Firewall")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        // Every installed app EXCEPT the ones marked blocked bypasses the tunnel
        // (normal, unrestricted network access). Blocked apps' traffic enters the
        // tunnel below and is simply never forwarded anywhere.
        val installedApps = packageManager.getInstalledApplications(0)
        for (app in installedApps) {
            if (app.packageName == packageName) continue // never block ourselves
            if (!blocked.contains(app.packageName)) {
                try {
                    builder.addDisallowedApplication(app.packageName)
                } catch (e: Exception) {
                    // package no longer installed / not allowed — safe to ignore
                }
            }
        }

        vpnInterface = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }

        if (vpnInterface == null) return
        running = true
        workerThread = Thread { runLoop() }.apply { start() }
    }

    private fun runLoop() {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        while (running) {
            try {
                val length = input.read(buffer)
                // Any packet that reaches here belongs to a BLOCKED app (allowed apps
                // never enter the tunnel at all). We intentionally do nothing with it —
                // not forwarding it anywhere is the "block" — and just loop for the next one.
                if (length <= 0) Thread.sleep(50)
            } catch (e: Exception) {
                running = false
            }
        }
    }

    private fun stopVpn() {
        running = false
        workerThread?.interrupt()
        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Firewall", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, SimpleFirewallService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall active")
            .setContentText("Blocking network access for selected apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
