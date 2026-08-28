package com.camremote.core.security

import com.camremote.core.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * With adb deliberately out of the picture, this is how the shared secret gets from the handset to
 * the control machine: the user taps Pair, and the client has a brief window to claim the token.
 * The window is the security boundary, so its edges are pinned down here.
 */
class PairingWindowTest {

    private val clock = FakeClock()
    private val window = PairingWindow(clock = clock, duration = 60.seconds) { "the-token" }

    @Test
    fun `is closed until the user opens it`() {
        assertFalse(window.isOpen())
        assertNull(window.claim())
    }

    @Test
    fun `hands over the token while open`() {
        window.open()

        assertTrue(window.isOpen())
        assertEquals("the-token", window.claim())
    }

    @Test
    fun `closes after a single successful claim`() {
        window.open()
        window.claim()

        // A window that stayed open for its full minute would let a second, unintended client pair.
        assertFalse(window.isOpen())
        assertNull(window.claim())
    }

    @Test
    fun `expires exactly when the duration has elapsed`() {
        window.open()
        clock.advance(60_000)

        // The window is half-open: [opened, opened + duration). That keeps "open" and
        // "remainingMillis() > 0" the same statement, with no ambiguous instant between them.
        assertFalse(window.isOpen())
        assertNull(window.claim())
    }

    @Test
    fun `is still open on the last millisecond before expiry`() {
        window.open()
        clock.advance(59_999)

        assertTrue(window.isOpen())
        assertEquals("the-token", window.claim())
    }

    @Test
    fun `reopening restarts the clock`() {
        window.open()
        clock.advance(60_000)
        window.open()

        assertTrue(window.isOpen())
    }

    @Test
    fun `reports the remaining time so the device can show a countdown`() {
        window.open()
        clock.advance(15_000)

        assertEquals(45_000, window.remainingMillis())
    }

    @Test
    fun `reports zero remaining when closed`() {
        assertEquals(0, window.remainingMillis())
    }
}
