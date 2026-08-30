package com.camremote.app.adapter

import com.camremote.core.logic.LanAddresses
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the address a control machine on the same network should connect to.
 *
 * Enumerating interfaces rather than asking `WifiManager` for the DHCP address, because the latter
 * is deprecated, IPv4-only by design, and wrong the moment the handset is reachable over something
 * that is not Wi-Fi — a USB tether or an overlay network such as Tailscale.
 *
 * With no setup screen to display this on, it goes into the agent's persistent notification
 * instead — the one place an operator can find the address without adb, even when mDNS discovery
 * is blocked by the network.
 *
 * Which of several addresses to prefer is decided by [LanAddresses], where it is tested.
 */
object LocalAddresses {

    fun firstLanIpv4(): String? = LanAddresses.preferred(candidates())

    private fun candidates(): List<LanAddresses.Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { LanAddresses.Candidate(networkInterface.name, it.hostAddress ?: "") }
            }
            .filter { it.address.isNotEmpty() }
            .toList()
    }.getOrDefault(emptyList())
}
