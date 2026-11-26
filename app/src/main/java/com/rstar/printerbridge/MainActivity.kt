package com.rstar.printerbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }

        val title = TextView(this).apply {
            text = "RStar Printer Bridge"
            textSize = 24f
        }
        statusText = TextView(this).apply {
            text = "Starting printer bridge..."
            textSize = 16f
            setPadding(0, 28, 0, 28)
        }

        val startButton = Button(this).apply {
            text = "Start Bridge"
            setOnClickListener { startBridge() }
        }
        val stopButton = Button(this).apply {
            text = "Stop Bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PrinterBridgeService::class.java))
                statusText.text = "Bridge stopped"
            }
        }
        val info = TextView(this).apply {
            text = "Local API: http://127.0.0.1:3001\nPrint endpoint: /api/print\nPrinter protocol: ESC/POS over TCP"
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(startButton)
        layout.addView(stopButton)
        layout.addView(info)
        setContentView(layout)

        requestNotificationPermissionIfNeeded()
        startBridge()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun startBridge() {
        val intent = Intent(this, PrinterBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "Bridge start requested.\nURL: http://127.0.0.1:3001"
    }
}
