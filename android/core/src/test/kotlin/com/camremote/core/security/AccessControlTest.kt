package com.camremote.core.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent listens on the local network, so this is the only thing standing between it and any
 * other device on the same Wi-Fi. Its behaviour is specified rather than assumed.
 */
class AccessControlTest {

    private val access = AccessControl { "s3cret-token" }

    @Test
    fun `accepts a correct bearer token`() {
        assertTrue(access.isAuthorized("Bearer s3cret-token"))
    }

    @Test
    fun `accepts the bearer scheme case-insensitively as RFC 7235 requires`() {
        assertTrue(access.isAuthorized("bearer s3cret-token"))
        assertTrue(access.isAuthorized("BEARER s3cret-token"))
    }

    @Test
    fun `rejects a missing header`() {
        assertFalse(access.isAuthorized(null))
    }

    @Test
    fun `rejects an empty or scheme-only header`() {
        assertFalse(access.isAuthorized(""))
        assertFalse(access.isAuthorized("Bearer"))
        assertFalse(access.isAuthorized("Bearer "))
    }

    @Test
    fun `rejects a different scheme`() {
        assertFalse(access.isAuthorized("Basic s3cret-token"))
    }

    @Test
    fun `rejects a wrong token`() {
        assertFalse(access.isAuthorized("Bearer wrong-token"))
    }

    @Test
    fun `rejects a token that is merely a prefix of the real one`() {
        // A comparison that stops at the shorter length would accept this.
        assertFalse(access.isAuthorized("Bearer s3cret"))
        assertFalse(access.isAuthorized("Bearer s3cret-token-and-more"))
    }

    @Test
    fun `reads the token on every call so rotation takes effect immediately`() {
        var token = "first"
        val rotating = AccessControl { token }
        assertTrue(rotating.isAuthorized("Bearer first"))

        token = "second"

        assertFalse(rotating.isAuthorized("Bearer first"))
        assertTrue(rotating.isAuthorized("Bearer second"))
    }
}
