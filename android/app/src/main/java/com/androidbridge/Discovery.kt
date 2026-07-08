package com.androidbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Bonjour/mDNS discovery of paired Macs on the LAN. Re-points a Mac's link at its CURRENT IP
 * after a DHCP change (matched by the TXT `room`), so no re-scan is needed.
 */
object Discovery {
    private const val TYPE = "_androidbridge._tcp."
    private const val TAG = "ABridge"

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var appContext: Context? = null
    private val lastHost = HashMap<String, String>()   // room -> last host

    fun start(c: Context) {
        if (nsd != null) return
        appContext = c.applicationContext
        val mgr = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        if (Prefs.macs(c).isEmpty()) return

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_androidbridge")) resolve(mgr, service)
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

    private fun resolve(mgr: NsdManager, service: NsdServiceInfo) {
        mgr.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val room = info.attributes["room"]?.let { String(it) } ?: return
                val ctx = appContext ?: return
                if (Prefs.macs(ctx).none { it.room == room }) return   // not one of ours
                val host = info.host?.hostAddress ?: return
                if (host.contains(":")) return                          // IPv4 only
                if (lastHost[room] == host) return                      // no change → don't churn
                lastHost[room] = host
                Log.i(TAG, "nsd resolved $room at $host:${info.port}")
                Links.updateIp(room, host, info.port)
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
