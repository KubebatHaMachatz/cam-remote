package com.camremote.app.setup

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the address a control machine on the same Wi-Fi should connect to.
 *
 * Enumerating interfaces rather than asking `WifiManager` for the DHCP address, because the latter
 * is deprecated, IPv4-only by design, and wrong the moment the handset is reachable over something
 * that is not Wi-Fi — a USB tether or an overlay network such as Tailscale, which is exactly how
 * this agent gets controlled from outside the LAN without a line of extra code.
 */
object LocalAddresses {

    fun firstLanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
