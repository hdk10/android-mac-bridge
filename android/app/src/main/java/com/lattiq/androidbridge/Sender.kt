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
        val lan = BridgeClient.isConnected
        // Dual-send: when LAN looks up, send it AND the relay as a backstop. This
        // covers the ~20s window where a half-open LAN socket still reads "connected"
        // after a network switch — the relay copy always lands. Mac dedups by id.
        if (lan) {
            BridgeClient.send(blob)
            lastPath = "LAN"
        } else {
            lastPath = "Internet Relay"
        }
        RelayClient.post(Prefs.relay(c), Prefs.room(c), blob)
        Log.i("ABridge", "send lan=$lan + relay (dedup by id)")
    }

    /** The channel the next notification would use right now. */
    fun currentChannel(): String = if (BridgeClient.isConnected) "LAN" else "Internet Relay"
}
