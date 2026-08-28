package com.camremote.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokensTest {

    @Test
    fun `mints unique tokens`() {
        val tokens = (1..1000).map { Tokens.newToken() }

        assertEquals(1000, tokens.toSet().size)
    }

    @Test
    fun `mints tokens that survive being typed by hand or put in a header`() {
        val token = Tokens.newToken()

        // The user may read this off the phone screen and type it, so it stays URL-safe and free of
        // characters that need quoting in a shell or an HTTP header.
        assertTrue(token.matches(Regex("^[A-Za-z0-9_-]+$")), token)
    }

    @Test
    fun `mints tokens long enough to resist guessing over a local network`() {
        // 24 random bytes; anything materially shorter would be worth brute-forcing on a LAN.
        assertTrue(Tokens.newToken().length >= 32)
    }
}
