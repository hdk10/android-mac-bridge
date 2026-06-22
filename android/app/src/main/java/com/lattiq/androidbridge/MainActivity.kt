package com.lattiq.androidbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Minimal config UI: enter Mac IP, grant notification access, connect. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)

        val ipField = EditText(this).apply {
            hint = "Mac LAN IP (e.g. 192.168.1.50)"
            setText(prefs.getString("mac_ip", ""))
        }
        val saveBtn = Button(this).apply { text = "Save & Connect" }
        val grantBtn = Button(this).apply { text = "Grant Notification Access" }
        status = TextView(this).apply { text = "status: idle" }

        saveBtn.setOnClickListener {
            val ip = ipField.text.toString().trim()
            prefs.edit().putString("mac_ip", ip).apply()
            if (ip.isNotEmpty()) BridgeClient.configure(ip)
            refreshStatus()
        }
        grantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(ipField); addView(saveBtn); addView(grantBtn); addView(status)
        })

        // auto-connect if previously configured
        prefs.getString("mac_ip", "")?.takeIf { it.isNotEmpty() }?.let { BridgeClient.configure(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = "status: ${BridgeClient.status}"
    }
}
