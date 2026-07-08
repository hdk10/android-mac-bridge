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

    // Off-LAN relay heartbeats are throttled to this cadence per Mac. The 30s tick
    // still drives snappy LAN presence; only the relay path is slowed to cut cost.
    private const val RELAY_HEARTBEAT_MS = 60_000L
    private val lastRelayBeat = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Presence heartbeat — deliberately cheaper than [broadcast], but never less
     * reliable for actual data (data always goes through [broadcast]'s dual-send).
     *
     *  - LAN up   -> send over LAN only; the LAN socket already proves presence, so
     *                no relay traffic at all (the common home/office case).
     *  - LAN down -> keep the Mac's presence alive over the relay, but:
     *                  * throttled to RELAY_HEARTBEAT_MS, and
     *                  * sent up the already-open listen socket (20:1 billing, no
     *                    Worker request) when possible, falling back to the exact
     *                    same POST path used today if that socket isn't ready.
     */
    fun heartbeat(c: Context, plaintextJson: String) {
        for (mac in Prefs.macs(c)) {
            val blob = runCatching { Crypto.encrypt(mac.key, plaintextJson) }.getOrNull() ?: continue
            if (Links.isConnected(mac.room)) {
                Links.send(mac.room, blob)
                lastRelayBeat.remove(mac.room)            // reset throttle for next off-LAN spell
                continue
            }
            val now = System.currentTimeMillis()
            val due = now - (lastRelayBeat[mac.room] ?: 0L) >= RELAY_HEARTBEAT_MS
            if (!due) continue
            lastRelayBeat[mac.room] = now
            if (!RelayClient.sendOverListen(mac.room, blob)) {
                RelayClient.post(mac.relay, mac.room, blob)   // reliable fallback (today's path)
            }
        }
    }

    /** Send an already-encrypted blob to one Mac (used for per-Mac control like "bye"). */
    fun sendTo(mac: Mac, blob: String) {
        if (Links.isConnected(mac.room)) Links.send(mac.room, blob)
        RelayClient.post(mac.relay, mac.room, blob)
    }

    fun currentChannel(c: Context): String =
        if (Prefs.macs(c).any { Links.isConnected(it.room) }) "LAN" else "Internet Relay"
}
