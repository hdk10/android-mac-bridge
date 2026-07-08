package com.androidbridge

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Off-LAN path: async POST an encrypted blob to the Cloudflare relay room. */
object RelayClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---- Mac→phone off-LAN receive: one persistent WS per Mac to /pair/<room>/listen ----

    /** Separate client: long-lived sockets need a ping, not a call timeout. */
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** (room, text) for Mac→phone messages arriving over the relay (mirrors Links.onTextMessage). */
    @Volatile var onTextMessage: ((String, String) -> Unit)? = null

    private class Listen(@Volatile var url: String) {
        @Volatile var ws: WebSocket? = null
        val connecting = AtomicBoolean(false)
    }

    private val listens = ConcurrentHashMap<String, Listen>()   // room -> receive socket

    /** Open/refresh a relay-listen socket for each Mac, drop sockets for any that are gone. */
    fun configureListen(macs: List<Mac>) {
        val rooms = macs.map { it.room }.toSet()
        listens.keys.filter { it !in rooms }.forEach { listens.remove(it)?.ws?.cancel() }
        macs.forEach { ensureListen(it) }
    }

    private fun ensureListen(mac: Mac) {
        if (mac.relay.isEmpty() || mac.room.isEmpty()) return
        val url = "wss://${mac.relay}/pair/${mac.room}/listen?role=phone"
        val l = listens.getOrPut(mac.room) { Listen(url) }
        if (l.url != url) { l.ws?.cancel(); l.ws = null; l.url = url }
        if (l.ws == null) openListen(mac.room)
    }

    /** Reconnect any dropped relay-listen sockets (called from the heartbeat). */
    fun ensureListenAll() {
        listens.forEach { (room, l) -> if (l.ws == null) openListen(room) }
    }

    fun closeAllListen() {
        listens.values.forEach { it.ws?.cancel() }
        listens.clear()
    }

    private fun openListen(room: String) {
        val l = listens[room] ?: return
        if (l.ws != null || !l.connecting.compareAndSet(false, true)) return
        val req = Request.Builder().url(l.url).header("User-Agent", "android-bridge/1.0").build()
        wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                l.ws = webSocket; l.connecting.set(false)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                onTextMessage?.invoke(room, text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                l.ws = null; l.connecting.set(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                l.ws = null
            }
        })
    }

    /**
     * Send a blob UP the already-open relay-listen socket for this room. The relay
     * forwards it to the paired Mac (opposite role). Used only for the presence
     * heartbeat: it's ~20x cheaper than a POST and adds no Worker request. Returns
     * false if the socket isn't ready — the caller then falls back to post().
     */
    fun sendOverListen(room: String, blob: String): Boolean {
        val ws = listens[room]?.ws ?: return false
        return runCatching { ws.send(blob) }.getOrDefault(false)
    }

    /**
     * Fire-and-forget. MUST be async: onNotificationPosted runs on the main thread,
     * and a synchronous OkHttp call there throws NetworkOnMainThreadException.
     */
    fun post(relayBase: String, room: String, blob: String) {
        if (relayBase.isEmpty() || room.isEmpty()) return
        val req = Request.Builder()
            .url("https://$relayBase/pair/$room/notify")
            .post(blob.toByteArray().toRequestBody())
            .header("User-Agent", "android-bridge/1.0")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ABridge", "relay POST failed", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { Log.i("ABridge", "relay POST -> ${it.code}") }
            }
        })
    }

    /**
     * Reachability probe (BLOCKING — call off the main thread). Returns true if the relay
     * delivered the blob to a connected Mac (response delivered >= 1).
     */
    fun ping(relayBase: String, room: String, blob: String): Boolean {
        if (relayBase.isEmpty() || room.isEmpty()) return false
        val req = Request.Builder()
            .url("https://$relayBase/pair/$room/notify")
            .post(blob.toByteArray().toRequestBody())
            .header("User-Agent", "android-bridge/1.0")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                org.json.JSONObject(body).optInt("delivered", 0) >= 1
            }
        } catch (e: Exception) {
            false
        }
    }
}
