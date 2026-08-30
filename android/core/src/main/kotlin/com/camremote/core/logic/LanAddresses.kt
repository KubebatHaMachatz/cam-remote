package com.camremote.core.logic

/**
 * Chooses which of a device's addresses to show an operator.
 *
 * A phone routinely holds several at once — Wi-Fi, mobile data, sometimes a VPN — and only one is
 * any use to a control machine on the same network. Showing the wrong one produces the most annoying
 * kind of failure: an agent that is working perfectly behind an address nothing can reach.
 *
 * Enumerating the interfaces is the adapter's job; ranking them is a decision, so it lives here where
 * it can be tested without a handset.
 */
object LanAddresses {

    data class Candidate(val interfaceName: String, val address: String)

    /** Best address, or null when the device has nothing reachable. */
    fun preferred(candidates: List<Candidate>): String? = candidates
        .filter { it.address.isUsable() }
        .minByOrNull { it.interfaceName.rank() }
        ?.address

    /**
     * Lower is better. Wi-Fi first, then wired, then anything else; mobile and tunnels last.
     *
     * A tunnel still scores — reaching the agent over an overlay network such as Tailscale is a
     * supported way to work, just not the one to advertise when a plain LAN address exists.
     */
    private fun String.rank(): Int {
        val name = lowercase()
        return when {
            name.startsWith("wlan") || name.startsWith("wifi") -> 0
            name.startsWith("eth") || name.startsWith("enp") -> 1
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> 3
            name.startsWith("tun") || name.startsWith("utun") || name.startsWith("ppp") -> 4
            else -> 2
        }
    }

    /** Excludes loopback, and the link-local address a failed DHCP leaves behind. */
    private fun String.isUsable(): Boolean =
        !startsWith("127.") && !startsWith("169.254.")
}
