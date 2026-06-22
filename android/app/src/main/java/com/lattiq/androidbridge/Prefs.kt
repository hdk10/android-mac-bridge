package com.lattiq.androidbridge

import android.content.Context

/** Central pairing config from the QR: LAN ip/port + secretbox key + relay room. */
object Prefs {
    private const val FILE = "bridge"

    fun ip(c: Context): String = sp(c).getString("mac_ip", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8765)
    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun room(c: Context): String = sp(c).getString("room", "") ?: ""
    fun relay(c: Context): String = sp(c).getString("relay", "") ?: ""
    fun isPaired(c: Context): Boolean = key(c).isNotEmpty() && room(c).isNotEmpty()

    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = sp(c).edit().putBoolean("paused", v).apply()

    fun save(c: Context, ip: String, port: Int, key: String, room: String, relay: String) {
        sp(c).edit()
            .putString("mac_ip", ip)
            .putInt("port", port)
            .putString("key", key)
            .putString("room", room)
            .putString("relay", relay)
            .putBoolean("paused", false)   // a fresh pair resumes forwarding
            .apply()
    }

    /** Unpair: forget the Mac entirely. Requires a new QR scan to resume. */
    fun clear(c: Context) = sp(c).edit().clear().apply()

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
