package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException

/**
 * Validation for Android property names arriving from the network.
 *
 * The reader passes the key to a process as a discrete argument, never through a shell, so
 * metacharacters are already inert. This exists anyway: it costs one regex, it turns a class of
 * question into a non-question, and it means a typo comes back as INVALID_PARAMS with the offending
 * text rather than as an empty result the caller has to puzzle over.
 */
object PropertyKeys {

    /** Android property names are dot-separated identifiers; nothing else is legal. */
    private val VALID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** Comfortably above the longest real property name, and far below anything abusive. */
    private const val MAX_LENGTH = 128

    /** @throws InvalidParamsException if [key] is not a plausible property name. */
    fun validate(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            throw InvalidParamsException("Property name must not be blank")
        }
        if (trimmed.length > MAX_LENGTH) {
            throw InvalidParamsException("Property name is longer than $MAX_LENGTH characters")
        }
        if (!VALID.matches(trimmed)) {
            throw InvalidParamsException(
                "'$trimmed' is not a valid property name; expected characters A-Z a-z 0-9 . _ -",
            )
        }
        return trimmed
    }
}
