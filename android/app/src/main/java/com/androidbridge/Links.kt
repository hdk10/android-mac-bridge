package com.androidbridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One LAN WebSocket per paired Mac, keyed by room id. Mac→phone messages arrive via onTextMessage. */
object Links {
    private val client = OkHttpClient.Builder()
        .pingInterval(12, TimeUnit.SECONDS)
        .build()

    private class Conn(@Volatile var url: String) {
        @Volatile var ws: WebSocket? = null
        val connecting = AtomicBoolean(false)
        val queue = ConcurrentLinkedQueue<String>()
    }

    private val conns = ConcurrentHashMap<String, Conn>()   // room -> connection

    /** (room, text) for Mac→phone control messages (e.g. "open on phone"). */
    @Volatile var onTextMessage: ((String, String) -> Unit)? = null

    /** Open links for the given Macs, drop links for any that are gone. */
    fun configure(macs: List<Mac>) {
        val rooms = macs.map { it.room }.toSet()
        conns.keys.filter { it !in rooms }.forEach { conns.remove(it)?.ws?.cancel() }
        macs.forEach { ensure(it) }
    }

    fun ensure(mac: Mac) {
        if (mac.ip.isEmpty()) return
        updateIp(mac.room, mac.ip, mac.port)
    }

    /** Point a room's link at a (possibly new) address and (re)connect. */
    fun updateIp(room: String, ip: String, port: Int) {
        if (ip.isEmpty()) return
        val url = "ws://$ip:$port"
        val conn = conns.getOrPut(room) { Conn(url) }
        if (conn.url != url) { conn.ws?.cancel(); conn.ws = null; conn.url = url }
        if (conn.ws == null) open(room)
    }

    fun isConnected(room: String): Boolean = conns[room]?.ws != null

    fun send(room: String, blob: String) {
        val conn = conns[room] ?: return
        val w = conn.ws
        if (w != null) w.send(blob) else { conn.queue.add(blob); open(room) }
    }

    /** Reconnect any dropped links (called from the heartbeat). */
    fun ensureAll() {
        conns.forEach { (room, conn) -> if (conn.ws == null) open(room) }
    }

    private fun open(room: String) {
        val conn = conns[room] ?: return
        if (conn.ws != null || !conn.connecting.compareAndSet(false, true)) return
        val req = Request.Builder().url(conn.url).build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                conn.ws = webSocket; conn.connecting.set(false)
                while (conn.queue.isNotEmpty()) conn.queue.poll()?.let { webSocket.send(it) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                onTextMessage?.invoke(room, text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conn.ws = null; conn.connecting.set(false)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conn.ws = null
            }
        })
    }
}
