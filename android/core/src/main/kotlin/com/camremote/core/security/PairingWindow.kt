package com.camremote.core.security

import com.camremote.core.port.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A brief, single-use window during which the agent will hand its token to a client that asks.
 *
 * This exists because the project deliberately does not use adb: without a shell on the device
 * there has to be some in-band way for the control machine to learn the shared secret. Requiring a
 * physical tap on the handset, bounding the window in time, and closing it after one claim keeps
 * that from becoming a permanent hole — someone on the same network can only pair if they are also
 * holding the phone at that moment.
 */
class PairingWindow(
    private val clock: Clock,
    private val duration: Duration = DEFAULT_DURATION,
    private val tokens: TokenStore,
) {

    private var openedAtMillis: Long? = null

    /** Called when the user taps Pair on the device. Restarts the window if one is already open. */
    fun open() {
        openedAtMillis = clock.nowMillis()
    }

    fun isOpen(): Boolean = remainingMillis() > 0

    /** Milliseconds left in the window, so the setup screen can show a countdown. */
    fun remainingMillis(): Long {
        val openedAt = openedAtMillis ?: return 0
        val elapsed = clock.nowMillis() - openedAt
        return (duration.inWholeMilliseconds - elapsed).coerceAtLeast(0)
    }

    /**
     * Returns the token and closes the window, or null when no window is open.
     *
     * Closing on success is the point: the window is for one client, not for everyone who happens
     * to be listening during the minute it is open.
     */
    fun claim(): String? {
        if (!isOpen()) return null
        openedAtMillis = null
        return tokens.currentToken()
    }

    /** Called when the user cancels, or when the service stops. */
    fun close() {
        openedAtMillis = null
    }

    companion object {
        val DEFAULT_DURATION = 60.seconds
    }
}
