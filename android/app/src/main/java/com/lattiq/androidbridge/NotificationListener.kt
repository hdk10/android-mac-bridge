package com.lattiq.androidbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

/** Captures every posted notification and forwards it to the Mac (LAN or relay). */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Skip ongoing/empty noise (music, charging, etc.)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val keyB64 = Prefs.key(this)
        Log.i(TAG, "notif ${sbn.packageName} paired=${keyB64.isNotEmpty()}")
        if (keyB64.isEmpty()) return  // not paired yet

        try {
            val otp = OtpExtractor.extract(title, text)
            val json = JSONObject().apply {
                put("app", sbn.packageName)
                put("title", title ?: "")
                put("text", text ?: "")
                put("otp", otp ?: JSONObject.NULL)
                put("time", System.currentTimeMillis())
            }
            val blob = Crypto.encrypt(keyB64, json.toString())
            Log.i(TAG, "encrypted ${blob.length}b lanConnected=${BridgeClient.isConnected}")
            Sender.send(this, blob)
        } catch (e: Throwable) {
            Log.e(TAG, "send failed", e)
        }
    }

    companion object { const val TAG = "ABridge" }
}
