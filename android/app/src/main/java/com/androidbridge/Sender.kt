package com.androidbridge

import android.content.Context

/**
 * Fan-out delivery: each notification is encrypted per Mac (different keys) and sent to every
 * paired Mac — over LAN when that Mac's socket is up, plus the relay as a backstop. Each Mac
 * de-duplicates by message id.
 */
object Sender {
    @Volatile var lastPath: String = "—"
        private set

    fun broadcast(c: Context, plaintextJson: String) {
        var anyLan = false
        for (mac in Prefs.macs(c)) {
            val blob = runCatching { Crypto.encrypt(mac.key, plaintextJson) }.getOrNull() ?: continue
            if (Links.isConnected(mac.room)) { Links.send(mac.room, blob); anyLan = true }
            RelayClient.post(mac.relay, mac.room, blob)   // backstop
        }
        lastPath = if (anyLan) "LAN" else "Internet Relay"
    }

    /** Send an already-encrypted blob to one Mac (used for per-Mac control like "bye"). */
    fun sendTo(mac: Mac, blob: String) {
        if (Links.isConnected(mac.room)) Links.send(mac.room, blob)
        RelayClient.post(mac.relay, mac.room, blob)
    }

    fun currentChannel(c: Context): String =
        if (Prefs.macs(c).any { Links.isConnected(it.room) }) "LAN" else "Internet Relay"
}
