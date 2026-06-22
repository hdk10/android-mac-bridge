package com.lattiq.androidbridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Config + live status: pair by QR, grant notification access, show channel (LAN/relay). */
class MainActivity : AppCompatActivity() {

    private lateinit var dot: TextView
    private lateinit var channel: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }  // live refresh
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Mac Bridge"; textSize = 24f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        val scanBtn = Button(this).apply { text = "📷  Scan QR to pair" }
        scanBtn.setOnClickListener { startActivity(Intent(this, ScannerActivity::class.java)) }
        val grantBtn = Button(this).apply { text = "Grant Notification Access" }
        grantBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        dot = TextView(this).apply { textSize = 18f; setPadding(0, 40, 0, 4) }
        channel = TextView(this).apply { textSize = 14f }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(title); addView(scanBtn); addView(grantBtn); addView(dot); addView(channel)
        })

        if (Prefs.ip(this).isNotEmpty()) BridgeClient.configure(Prefs.ip(this), Prefs.port(this))
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun refresh() {
        if (!Prefs.isPaired(this)) {
            dot.text = "🔴  Not paired"
            channel.text = "Scan the QR on your Mac to pair"
            return
        }
        val ch = Sender.currentChannel()  // LAN if socket up, else Internet Relay
        dot.text = "🟢  Connected  ·  via $ch"
        channel.text = "Last sent via: ${Sender.lastPath}"
    }
}
