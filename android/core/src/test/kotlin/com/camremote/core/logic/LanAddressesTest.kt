package com.camremote.core.logic

import com.camremote.core.logic.LanAddresses.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The setup screen tells the operator which address to connect to, and getting it wrong is a
 * frustrating way to fail: the agent is working perfectly and the address on screen is unreachable.
 *
 * A phone routinely has several addresses at once — Wi-Fi, mobile data, and possibly a VPN — and
 * only one of them is any use to a control machine on the same Wi-Fi.
 */
class LanAddressesTest {

    @Test
    fun `prefers wifi over mobile data`() {
        // The failure this exists to prevent: a Samsung with mobile data up showing its carrier
        // address, which nothing on the local network can reach.
        val chosen = LanAddresses.preferred(
            listOf(
                Candidate("rmnet_data0", "10.183.44.7"),
                Candidate("wlan0", "192.168.1.42"),
            ),
        )

        assertEquals("192.168.1.42", chosen)
    }

    @Test
    fun `prefers wifi over a vpn tunnel`() {
        val chosen = LanAddresses.preferred(
            listOf(
                Candidate("tun0", "10.8.0.2"),
                Candidate("wlan0", "192.168.1.42"),
            ),
        )

        assertEquals("192.168.1.42", chosen)
    }

    @Test
    fun `accepts ethernet, which is what an emulator and a dev board present`() {
        val chosen = LanAddresses.preferred(listOf(Candidate("eth0", "10.0.2.15")))

        assertEquals("10.0.2.15", chosen)
    }

    @Test
    fun `ranks wifi above ethernet when a device has both`() {
        val chosen = LanAddresses.preferred(
            listOf(Candidate("eth0", "10.0.2.15"), Candidate("wlan0", "192.168.1.42")),
        )

        assertEquals("192.168.1.42", chosen)
    }

    @Test
    fun `still answers when only a tunnel is up`() {
        // An overlay network such as Tailscale is a perfectly good way to reach the agent -- it is
        // simply not the first choice when a plain LAN address exists.
        assertEquals("100.64.0.9", LanAddresses.preferred(listOf(Candidate("tun0", "100.64.0.9"))))
    }

    @Test
    fun `ignores loopback`() {
        assertNull(LanAddresses.preferred(listOf(Candidate("lo", "127.0.0.1"))))
    }

    @Test
    fun `ignores link-local addresses from a failed dhcp`() {
        assertNull(LanAddresses.preferred(listOf(Candidate("wlan0", "169.254.11.2"))))
    }

    @Test
    fun `answers nothing when the device is offline`() {
        assertNull(LanAddresses.preferred(emptyList()))
    }

    @Test
    fun `keeps the first of two equally good addresses`() {
        val chosen = LanAddresses.preferred(
            listOf(Candidate("wlan0", "192.168.1.42"), Candidate("wlan1", "192.168.1.43")),
        )

        assertEquals("192.168.1.42", chosen)
    }

    @Test
    fun `recognises the usual mobile interface names`() {
        listOf("rmnet0", "rmnet_data2", "ccmni0", "pdp_ip0").forEach { mobile ->
            assertEquals(
                "192.168.1.42",
                LanAddresses.preferred(
                    listOf(Candidate(mobile, "10.183.44.7"), Candidate("wlan0", "192.168.1.42")),
                ),
                "expected wlan0 to beat $mobile",
            )
        }
    }
}
