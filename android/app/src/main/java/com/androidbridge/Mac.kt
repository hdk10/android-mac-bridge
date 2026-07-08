package com.androidbridge

import org.json.JSONObject

/** One paired Mac. `id` == relay room (unique per Mac). */
data class Mac(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val key: String,
    val room: String,
    val relay: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("port", port)
        put("key", key); put("room", room); put("relay", relay)
    }

    companion object {
        fun fromJson(o: JSONObject) = Mac(
            id = o.optString("id"),
            name = o.optString("name", "Mac"),
            ip = o.optString("ip"),
            port = o.optInt("port", 8765),
            key = o.optString("key"),
            room = o.optString("room"),
            relay = o.optString("relay"),
        )
    }
}
