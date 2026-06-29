package com.lattiq.androidbridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import java.util.UUID

/** Paired Macs (multi-device) + this phone's identity. */
object Prefs {
    private const val FILE = "bridge"

    fun macs(c: Context): List<Mac> {
        sp(c).getString("macs", null)?.let { return parse(it) }
        // migrate a pre-multi single pairing into the list
        val key = sp(c).getString("key", "") ?: ""
        val room = sp(c).getString("room", "") ?: ""
        if (key.isNotEmpty() && room.isNotEmpty()) {
            val m = Mac(
                id = room, name = sp(c).getString("mac_name", "Mac") ?: "Mac",
                ip = sp(c).getString("mac_ip", "") ?: "", port = sp(c).getInt("port", 8765),
                key = key, room = room, relay = sp(c).getString("relay", "") ?: ""
            )
            saveMacs(c, listOf(m))
            return listOf(m)
        }
        return emptyList()
    }

    fun addMac(c: Context, mac: Mac) = saveMacs(c, macs(c).filter { it.id != mac.id } + mac)
    fun removeMac(c: Context, id: String) = saveMacs(c, macs(c).filter { it.id != id })
    fun clearMacs(c: Context) = saveMacs(c, emptyList())
    fun isPaired(c: Context): Boolean = macs(c).isNotEmpty()

    fun paused(c: Context): Boolean = sp(c).getBoolean("paused", false)
    fun setPaused(c: Context, v: Boolean) = sp(c).edit().putBoolean("paused", v).apply()

    /** Last clipboard text received from a Mac (for the home-screen card). */
    fun lastClip(c: Context): String? = sp(c).getString("last_clip", null)?.takeIf { it.isNotEmpty() }
    fun setLastClip(c: Context, v: String) = sp(c).edit().putString("last_clip", v).apply()

    fun deviceId(c: Context): String {
        var id = sp(c).getString("device_id", "") ?: ""
        if (id.isEmpty()) { id = UUID.randomUUID().toString().take(12); sp(c).edit().putString("device_id", id).apply() }
        return id
    }

    fun deviceName(c: Context): String =
        (Settings.Global.getString(c.contentResolver, "device_name")?.takeIf { it.isNotBlank() }) ?: Build.MODEL

    private fun saveMacs(c: Context, list: List<Mac>) {
        sp(c).edit().putString("macs", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
    private fun parse(raw: String): List<Mac> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { Mac.fromJson(a.getJSONObject(it)) }
    }

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
