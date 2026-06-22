package com.lattiq.androidbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Bonjour/mDNS discovery of the Mac's LAN service. Lets the phone re-find the
 * Mac's CURRENT IP after a DHCP change without re-scanning the QR. Only adopts
 * a service whose TXT `room` matches our paired room (so we connect to the right
 * Mac, never someone else's).
 */
object Discovery {
    private const val TYPE = "_androidbridge._tcp."
    private const val TAG = "ABridge"

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start(c: Context) {
        if (nsd != null) return  // already running
        val mgr = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val room = Prefs.room(c)
        if (room.isEmpty()) return  // not paired

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_androidbridge")) resolve(mgr, service, room)
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { mgr.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        discovery = listener
        nsd = mgr
        try {
            mgr.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "nsd discover failed", e); nsd = null; discovery = null
        }
    }

    @Volatile private var lastHost: String? = null

    private fun resolve(mgr: NsdManager, service: NsdServiceInfo, room: String) {
        mgr.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val svcRoom = info.attributes["room"]?.let { String(it) } ?: ""
                if (svcRoom != room) return            // a different Mac — ignore
                val host = info.host?.hostAddress ?: return
                if (host.contains(":")) return         // IPv4 only (skip IPv6 link-local)
                if (host == lastHost) return           // already pointed here — don't churn the socket
                lastHost = host
                Log.i(TAG, "nsd resolved Mac at $host:${info.port} (room match)")
                BridgeClient.configure(host, info.port)
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
        })
    }

    fun stop() {
        val mgr = nsd; val l = discovery
        if (mgr != null && l != null) {
            try { mgr.stopServiceDiscovery(l) } catch (e: Exception) { /* ignore */ }
        }
        nsd = null; discovery = null
    }
}
