package com.camremote.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokensTest {

    @Test
    fun `mints a four character token`() {
        // Short by explicit choice: this is a proof of concept, and a token this length can be read
        // off the phone's screen and typed in a second. See the security note in docs/DESIGN.md for
        // what that costs.
        repeat(50) { assertEquals(4, Tokens.newToken().length) }
    }

    @Test
    fun `avoids characters that are misread when copying from a screen`() {
        val alphabet = (1..500).flatMap { Tokens.newToken().toList() }.toSet()

        // A four-character token is meant to be typed, so the pairs that get confused by eye --
        // O and 0, I and 1 -- are excluded rather than left to trip the operator up.
        assertTrue(alphabet.none { it in "O0I1l" }, "unexpected characters: $alphabet")
    }

    @Test
    fun `mints tokens that survive being typed by hand or put in a header`() {
        val token = Tokens.newToken()

        // URL-safe and free of anything needing quoting in a shell or an HTTP header.
        assertTrue(token.matches(Regex("^[A-Za-z0-9]{4}$")), token)
    }

    @Test
    fun `draws on the whole alphabet rather than a corner of it`() {
        val alphabet = (1..2000).flatMap { Tokens.newToken().toList() }.toSet()

        // Guards against a broken generator that only ever emits a handful of characters, which
        // would shrink an already small keyspace to nothing.
        assertEquals(Tokens.ALPHABET.toSet(), alphabet)
    }

    @Test
    fun `does not repeat itself constantly`() {
        // With a keyspace this small, collisions among many draws are expected and fine -- but a
        // generator returning the same value every time would pass every other test here.
        val tokens = (1..200).map { Tokens.newToken() }

        assertTrue(tokens.toSet().size > 100, "only ${tokens.toSet().size} distinct tokens in 200")
    }
}
