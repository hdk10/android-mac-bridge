package com.lattiq.androidbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.util.UUID

/**
 * Transparent, no-UI share-sheet target: the primary phone→Mac path.
 * Receives shared text/plain, wraps it as a "clip" message and broadcasts it (encrypted per Mac).
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() else null

        // Always finish(): this is a transparent, no-UI activity and must never linger.
        if (!Prefs.isPaired(this)) {
            Toast.makeText(this, "Pair a Mac first", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val json = JSONObject().apply {
            put("type", "clip")
            put("clip", text)
            put("id", UUID.randomUUID().toString())
            put("did", Prefs.deviceId(this@ShareReceiverActivity))
            put("dname", Prefs.deviceName(this@ShareReceiverActivity))
            put("time", System.currentTimeMillis())
        }
        // Sender.broadcast is non-blocking (Links.send + async RelayClient.post), safe on the main thread.
        Sender.broadcast(this, json.toString())
        Toast.makeText(this, "Sent to Mac", Toast.LENGTH_SHORT).show()
        finish()
    }
}
