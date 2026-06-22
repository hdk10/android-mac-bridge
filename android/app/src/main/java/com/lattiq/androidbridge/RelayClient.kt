package com.lattiq.androidbridge

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Off-LAN path: async POST an encrypted blob to the Cloudflare relay room. */
object RelayClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fire-and-forget. MUST be async: onNotificationPosted runs on the main thread,
     * and a synchronous OkHttp call there throws NetworkOnMainThreadException.
     */
    fun post(relayBase: String, room: String, blob: String) {
        if (relayBase.isEmpty() || room.isEmpty()) return
        val req = Request.Builder()
            .url("https://$relayBase/pair/$room/notify")
            .post(blob.toByteArray().toRequestBody())
            .header("User-Agent", "android-bridge/1.0")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ABridge", "relay POST failed", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { Log.i("ABridge", "relay POST -> ${it.code}") }
            }
        })
    }

    /**
     * Reachability probe (BLOCKING — call off the main thread). Returns true if the relay
     * delivered the blob to a connected Mac (response delivered >= 1).
     */
    fun ping(relayBase: String, room: String, blob: String): Boolean {
        if (relayBase.isEmpty() || room.isEmpty()) return false
        val req = Request.Builder()
            .url("https://$relayBase/pair/$room/notify")
            .post(blob.toByteArray().toRequestBody())
            .header("User-Agent", "android-bridge/1.0")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                org.json.JSONObject(body).optInt("delivered", 0) >= 1
            }
        } catch (e: Exception) {
            false
        }
    }
}
