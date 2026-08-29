package com.camremote.app.setup

import com.camremote.core.logic.LanAddresses
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the address a control machine on the same network should connect to.
 *
 * Enumerating interfaces rather than asking `WifiManager` for the DHCP address, because the latter
 * is deprecated, IPv4-only by design, and wrong the moment the handset is reachable over something
 * that is not Wi-Fi — a USB tether or an overlay network such as Tailscale, which is exactly how
 * this agent gets controlled from outside the LAN without a line of extra code.
 *
 * Which of several addresses to prefer is decided by [LanAddresses], where it is tested.
 */
object LocalAddresses {

    /** The best address to advertise to an operator, or null when the device is offline. */
    fun firstLanIpv4(): String? = LanAddresses.preferred(candidates())

    /** Every IPv4 address on an interface that is up, paired with that interface's name. */
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
