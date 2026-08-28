package com.camremote.app.adapter

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Announces the agent on the local network over mDNS/DNS-SD.
 *
 * This is what replaces `adb forward` in a deliberately adb-free design: without it, an operator
 * would have to find the handset's IP address by hand every time DHCP changed its mind. The control
 * application browses for `_camremote._tcp` and gets an address and port back.
 *
 * Registration is best-effort. Plenty of networks block multicast, and guest networks isolate
 * clients entirely, so a failure here is logged and shrugged off — the CLI's `--host` option always
 * works and the README says so.
 */
class NsdServiceAdvertiser(context: Context) {

    private val nsd = context.getSystemService<NsdManager>()
    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(serviceName: String, port: Int, attributes: Map<String, String>) {
        val manager = nsd ?: return
        stop()

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            attributes.forEach { (key, value) -> setAttribute(key, value) }
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertising ${info.serviceName} on $SERVICE_TYPE port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed ($errorCode); clients must use --host")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        listener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure { Log.w(TAG, "Could not start mDNS advertising", it) }
    }

    fun stop() {
        val manager = nsd ?: return
        listener?.let { runCatching { manager.unregisterService(it) } }
        listener = null
    }

    companion object {
        /** DNS-SD service type. The client browses for exactly this. */
        const val SERVICE_TYPE = "_camremote._tcp"
        private const val TAG = "NsdServiceAdvertiser"
    }
}
