package com.lattiq.androidbridge

import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

/** Captures every notification and broadcasts it (encrypted per Mac) to all paired Macs. */
class NotificationListener : NotificationListenerService() {

    private val hb = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            val ctx = this@NotificationListener
            val macs = Prefs.macs(ctx)
            Links.configure(macs)              // open/refresh LAN links to every paired Mac
            RelayClient.configureListen(macs)  // open/refresh relay receive sockets (off-LAN)
            if (Prefs.isPaired(ctx)) {
                val ping = JSONObject().apply {
                    put("type", "ping")
                    put("did", Prefs.deviceId(ctx)); put("dname", Prefs.deviceName(ctx))
                }
                Sender.broadcast(ctx, ping.toString())
            }
            hb.postDelayed(this, 30_000)
        }
    }

    override fun onListenerConnected() {
        Discovery.start(applicationContext)
        Links.configure(Prefs.macs(this))
        RelayClient.configureListen(Prefs.macs(this))
        Links.onTextMessage = { room, text -> handleFromMac(room, text) }
        RelayClient.onTextMessage = { room, text -> handleFromMac(room, text) }
        hb.removeCallbacks(heartbeat); hb.post(heartbeat)
    }

    override fun onListenerDisconnected() {
        Discovery.stop()
        hb.removeCallbacks(heartbeat)
        Links.onTextMessage = null
        RelayClient.onTextMessage = null
        RelayClient.closeAllListen()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        if (!Prefs.isPaired(this) || Prefs.paused(this)) return

        try {
            val otp = OtpExtractor.extract(title, text)
            val json = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("app", sbn.packageName)
                put("title", title ?: ""); put("text", text ?: "")
                put("otp", otp ?: JSONObject.NULL)
                put("time", System.currentTimeMillis())
                put("icon", appIconBase64(sbn.packageName) ?: JSONObject.NULL)
                put("key", sbn.key)
                put("did", Prefs.deviceId(this@NotificationListener))
                put("dname", Prefs.deviceName(this@NotificationListener))
            }
            Sender.broadcast(this, json.toString())   // encrypts per Mac + sends to all
        } catch (e: Throwable) {
            Log.e(TAG, "broadcast failed", e)
        }
    }

    // --- Mac→phone control (per room/Mac) ---
    private fun handleFromMac(room: String, blob: String) {
        val mac = Prefs.macs(this).firstOrNull { it.room == room } ?: return
        val json = Crypto.decrypt(mac.key, blob) ?: return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("type")) {
            "open" -> {
                val notifKey = o.optString("key"); val pkg = o.optString("app")
                hb.post { openOnPhone(notifKey, pkg) }
            }
            "clip" -> {
                val clip = o.optString("clip")
                if (clip.isEmpty()) return
                if (clip == lastAppliedClip) return   // echo-guard: don't re-apply our own value
                hb.post { applyClip(clip) }
            }
        }
    }

    /** Write Mac-sent text to the phone clipboard (main thread). Silent — Android shows its own chip. */
    private fun applyClip(clip: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Mac Bridge", clip))
            lastAppliedClip = clip
            Prefs.setLastClip(this, clip)
        } catch (e: Throwable) {
            Log.e(TAG, "applyClip failed", e)
        }
    }

    private fun openOnPhone(notifKey: String, pkg: String) {
        val sbn = runCatching { activeNotifications?.firstOrNull { it.key == notifKey } }.getOrNull()
        if (sbn != null) {
            runCatching { sbn.notification.contentIntent?.send() }.onSuccess { return }
        }
        if (pkg.isNotEmpty()) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
            }
        }
    }

    private fun appIconBase64(pkg: String): String? {
        iconCache[pkg]?.let { return it }
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(bmp))
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP).also { iconCache[pkg] = it }
        } catch (e: Exception) { null }
    }

    companion object {
        const val TAG = "ABridge"
        private val iconCache = HashMap<String, String>()
        /** Last clip we wrote to the phone clipboard — guards against re-applying the same value. */
        @Volatile var lastAppliedClip: String? = null
    }
}
