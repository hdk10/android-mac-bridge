package com.lattiq.androidbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Captures every posted notification and forwards it to the Mac over the LAN. */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Skip ongoing/empty noise (music, charging, etc.)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val otp = OtpExtractor.extract(title, text)

        val token = Prefs.token(this).ifEmpty { BuildConfig.BRIDGE_TOKEN }
        val json = JSONObject().apply {
            put("token", token)
            put("app", sbn.packageName)
            put("title", title ?: "")
            put("text", text ?: "")
            put("otp", otp ?: JSONObject.NULL)
            put("time", System.currentTimeMillis())
        }
        BridgeClient.send(json.toString())
    }
}
