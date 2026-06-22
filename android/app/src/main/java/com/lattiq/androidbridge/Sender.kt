package com.lattiq.androidbridge

import android.content.Context
import android.util.Log

/**
 * Hybrid delivery: prefer the LAN socket (fast, private, free); fall back to the
 * relay when off-LAN. OkHttp's ping interval on BridgeClient drops a half-open
 * LAN socket within ~20s of a network change, after which isConnected flips false
 * and we route via the relay.
 */
object Sender {
    /** Path used for the most recent send — surfaced in the UI. */
    @Volatile var lastPath: String = "—"
        private set

    fun send(c: Context, blob: String) {
        if (BridgeClient.isConnected) {
            lastPath = "LAN"
            Log.i("ABridge", "send via LAN")
            BridgeClient.send(blob)
        } else {
            lastPath = "Internet Relay"
            val relay = Prefs.relay(c); val room = Prefs.room(c)
            RelayClient.post(relay, room, blob)
            Log.i("ABridge", "send via relay relay=$relay room=$room")
        }
    }

    /** The channel the next notification would use right now. */
    fun currentChannel(): String = if (BridgeClient.isConnected) "LAN" else "Internet Relay"
}
