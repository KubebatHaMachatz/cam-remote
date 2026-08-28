package com.camremote.core.security

import java.security.SecureRandom

/**
 * Mints the shared secret the agent and the control application use.
 *
 * **This is a four-character token, and that is a deliberate proof-of-concept choice.** It is short
 * enough to read off the phone's screen and type, which is the point: pairing over the network is
 * the normal path, and this is the fallback that has to be pleasant by hand.
 *
 * The cost is real and worth stating plainly: four characters from a 32-symbol alphabet is about a
 * million possibilities, which anyone on the same network can exhaust in well under a minute. It
 * protects against a neighbour stumbling onto the port, not against someone who wants in. Restoring
 * a serious secret is a one-line change to [LENGTH] — nothing else in the project depends on the
 * length — and `docs/DESIGN.md` records the trade.
 *
 * The alphabet omits the characters that are misread when copying from a screen (`O`/`0`, `I`/`1`),
 * and is otherwise URL-safe, so the token needs no escaping in a header, a shell, or a config file.
 */
object Tokens {

    /** Unambiguous uppercase letters and digits: no O, I, 0 or 1. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Raise this to lengthen the token; nothing else needs to change. */
    const val LENGTH = 4

    private val random = SecureRandom()

    fun newToken(): String = buildString(LENGTH) {
        // nextInt(bound) rather than an index derived by modulo, which would favour the start of
        // the alphabet and quietly shrink an already small keyspace.
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
