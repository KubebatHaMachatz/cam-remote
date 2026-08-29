package com.camremote.core.security

/**
 * Supplies the currently valid bearer token.
 *
 * A port rather than a plain string so the token can be rotated at runtime without rebuilding the
 * server, and so tests need nothing more than a lambda.
 */
fun interface TokenStore {
    /** The token that is valid right now. Read per request, so rotation takes effect at once. */
    fun currentToken(): String
}

/**
 * Decides whether a request may proceed.
 *
 * The agent listens on the local network, so every request except the pairing handshake carries a
 * bearer token. The comparison is length-safe and constant-time: a naive `==` on strings can leak
 * how many leading characters were correct through timing, which is exactly the kind of detail that
 * is free to get right at the start and awkward to retrofit.
 */
class AccessControl(private val tokens: TokenStore) {

    /** True when [authorizationHeader] carries the current bearer token. */
    fun isAuthorized(authorizationHeader: String?): Boolean {
        val presented = extractBearerToken(authorizationHeader) ?: return false
        return constantTimeEquals(presented, tokens.currentToken())
    }

    /** Pulls the credential out of a `Bearer <token>` header, or null if it is not one. */
    private fun extractBearerToken(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(' ', limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].equals(BEARER_SCHEME, ignoreCase = true)) return null
        return parts[1].trim().ifEmpty { null }
    }

    /**
     * Compares two tokens without leaking how far they matched.
     *
     * A plain `==` returns as soon as two characters differ, and the time that takes is
     * measurable over a network — enough, with patience, to recover a token one character at a
     * time. Folding the length difference into the result rather than returning early on it
     * closes the same hole for length.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.encodeToByteArray()
        val right = b.encodeToByteArray()
        // Fold the length difference into the result instead of returning early on it.
        var difference = left.size xor right.size
        for (i in left.indices) {
            difference = difference or (left[i].toInt() xor right[i % right.size.coerceAtLeast(1)].toInt())
        }
        return difference == 0 && left.size == right.size
    }

    private companion object {
        const val BEARER_SCHEME = "Bearer"
    }
}
