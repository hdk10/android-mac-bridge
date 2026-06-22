package com.lattiq.androidbridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Config screen. Primary path: "Scan QR to pair" (camera reads the Mac's QR).
 * Manual IP entry kept as a fallback. Notification access still granted here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var paired: TextView
    private lateinit var ipField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "Mac Bridge"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val scanBtn = Button(this).apply { text = "📷  Scan QR to pair" }
        scanBtn.setOnClickListener { startActivity(Intent(this, ScannerActivity::class.java)) }

        val grantBtn = Button(this).apply { text = "Grant Notification Access" }
        grantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        paired = TextView(this).apply { setPadding(0, 32, 0, 8) }
        status = TextView(this)

        // --- manual fallback ---
        val manualLabel = TextView(this).apply {
            text = "— or set IP manually —"
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 8)
        }
        ipField = EditText(this).apply {
            hint = "Mac LAN IP (e.g. 192.168.1.50)"
            setText(Prefs.ip(this@MainActivity))
        }
        val saveBtn = Button(this).apply { text = "Save & Connect" }
        saveBtn.setOnClickListener {
            val ip = ipField.text.toString().trim()
            if (ip.isNotEmpty()) {
                Prefs.save(this, ip, Prefs.port(this), Prefs.token(this))
                BridgeClient.configure(ip, Prefs.port(this))
            }
            refresh()
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(title)
            addView(scanBtn)
            addView(grantBtn)
            addView(paired)
            addView(status)
            addView(manualLabel)
            addView(ipField)
            addView(saveBtn)
        })

        // auto-connect if already paired
        if (Prefs.ip(this).isNotEmpty()) BridgeClient.configure(Prefs.ip(this), Prefs.port(this))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        paired.text = if (Prefs.isPaired(this))
            "Paired: ${Prefs.ip(this)}:${Prefs.port(this)}"
        else "Not paired — scan the QR on your Mac"
        status.text = "status: ${BridgeClient.status}"
    }
}
