package com.lattiq.androidbridge

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Captures every posted notification and forwards it to the Mac (LAN or relay). */
class NotificationListener : NotificationListenerService() {

    private val hb = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            val key = Prefs.key(this@NotificationListener)
            if (key.isNotEmpty()) {
                runCatching { Crypto.encrypt(key, "{\"type\":\"ping\"}") }.getOrNull()
                    ?.let { Sender.send(this@NotificationListener, it) }
            }
            hb.postDelayed(this, 30_000)   // keeps the Mac's "Connected" presence alive
        }
    }

    override fun onListenerConnected() {
        // Keep the LAN socket pointed at the Mac's current IP even if DHCP changed it.
        Discovery.start(applicationContext)
        if (Prefs.ip(this).isNotEmpty()) BridgeClient.configure(Prefs.ip(this), Prefs.port(this))
        hb.removeCallbacks(heartbeat); hb.post(heartbeat)
    }

    override fun onListenerDisconnected() {
        Discovery.stop()
        hb.removeCallbacks(heartbeat)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Skip ongoing/empty noise (music, charging, etc.)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val keyB64 = Prefs.key(this)
        if (keyB64.isEmpty()) return            // not paired
        if (Prefs.paused(this)) return          // forwarding paused by the user

        try {
            val otp = OtpExtractor.extract(title, text)
            val json = JSONObject().apply {
                put("id", UUID.randomUUID().toString())  // dedup across dual-send (LAN+relay)
                put("app", sbn.packageName)
                put("title", title ?: "")
                put("text", text ?: "")
                put("otp", otp ?: JSONObject.NULL)
                put("time", System.currentTimeMillis())
                put("icon", appIconBase64(sbn.packageName) ?: JSONObject.NULL)
            }
            val blob = Crypto.encrypt(keyB64, json.toString())
            Sender.send(this, blob)
        } catch (e: Throwable) {
            Log.e(TAG, "send failed", e)
        }
    }

    /** Source app's launcher icon as a small base64 PNG, cached per package. */
    private fun appIconBase64(pkg: String): String? {
        iconCache[pkg]?.let { return it }
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bmp))
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP).also { iconCache[pkg] = it }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val TAG = "ABridge"
        private val iconCache = HashMap<String, String>()
    }
}
