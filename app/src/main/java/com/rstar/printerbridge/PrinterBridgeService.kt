package com.rstar.printerbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class PrinterBridgeService : Service() {
    private var server: LocalHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        try {
            server = LocalHttpServer()
            server?.start()
        } catch (e: Exception) {
            android.util.Log.e("RStarBridge", "Could not start local server", e)
            stopSelf()
        }
    }

    private fun startInForeground() {
        val channelId = "rstar_printer_bridge"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "RStar Printer Bridge",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("RStar Printer Bridge")
                .setContentText("Printer bridge is running on 127.0.0.1:3001")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RStar Printer Bridge")
                .setContentText("Printer bridge is running on 127.0.0.1:3001")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stopServer()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
