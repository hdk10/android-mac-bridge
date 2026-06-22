package com.lattiq.androidbridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Single shared WebSocket to the Mac. Queues messages while disconnected and reconnects lazily. */
object BridgeClient {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var url: String = ""
    private val connecting = AtomicBoolean(false)
    private val queue = ConcurrentLinkedQueue<String>()

    @Volatile var status: String = "idle"
        private set

    /** True when a live LAN socket exists — Sender uses this to pick LAN vs relay. */
    val isConnected: Boolean
        get() = ws != null

    fun configure(macIp: String, port: Int = 8765) {
        val next = "ws://$macIp:$port"
        if (next != url) {           // endpoint changed (re-pair) → drop stale socket
            ws?.cancel(); ws = null
        }
        url = next
        open()
    }

    private fun open() {
        if (url.isEmpty() || ws != null) return
        if (!connecting.compareAndSet(false, true)) return
        status = "connecting"
        val req = Request.Builder().url(url).build()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                connecting.set(false)
                status = "connected"
                while (queue.isNotEmpty()) queue.poll()?.let { webSocket.send(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                connecting.set(false)
                status = "disconnected"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                status = "disconnected"
            }
        })
    }

    fun send(msg: String) {
        val w = ws
        if (w != null) {
            w.send(msg)
        } else {
            queue.add(msg)
            open() // lazy reconnect
        }
    }
}
