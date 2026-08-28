package com.camremote.core.security

import java.security.SecureRandom
import java.util.Base64

/**
 * Mints the shared secret the agent and the control application use.
 *
 * URL-safe and unpadded because it travels in an `Authorization` header, gets written into a config
 * file, and may be read off the phone's screen and typed by hand — none of which tolerate characters
 * that need escaping.
 */
object Tokens {

    private const val TOKEN_BYTES = 24

    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
