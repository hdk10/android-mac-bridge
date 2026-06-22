package com.lattiq.androidbridge

import android.content.Context

/** Central pairing config: Mac IP, port, shared token. Written by QR scan or manual entry. */
object Prefs {
    private const val FILE = "bridge"

    fun ip(c: Context): String = sp(c).getString("mac_ip", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 8765)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun isPaired(c: Context): Boolean = ip(c).isNotEmpty() && token(c).isNotEmpty()

    fun save(c: Context, ip: String, port: Int, token: String) {
        sp(c).edit()
            .putString("mac_ip", ip)
            .putInt("port", port)
            .putString("token", token)
            .apply()
    }

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
