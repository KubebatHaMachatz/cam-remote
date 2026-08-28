package com.camremote.core.logic

import com.camremote.core.port.Clock
import com.camremote.core.protocol.InvalidParamsException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Names photo files and mints their download ids.
 *
 * Both are decisions rather than I/O, so they live here where they can be tested, leaving the
 * filesystem adapter with nothing but file operations.
 */
object PhotoNaming {

    private val TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC)

    private val SAFE_FILENAME = Regex("^[A-Za-z0-9._-]+$")
    private const val MAX_FILENAME_LENGTH = 128
    private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")

    private val random = SecureRandom()

    /**
     * Returns [requested] if it is a safe bare filename, otherwise a generated one.
     *
     * Generated names are UTC: the handset and the control machine are frequently in different time
     * zones, and a name that sorts chronologically is worth more than one that looks local.
     */
    fun filenameFor(clock: Clock, requested: String?): String {
        if (requested == null) {
            return "camremote-${TIMESTAMP.format(Instant.ofEpochMilli(clock.nowMillis()))}.jpg"
        }

        val name = requested.trim()
        if (name.isEmpty()) {
            throw InvalidParamsException("Parameter 'filename' must not be blank")
        }
        if (name.length > MAX_FILENAME_LENGTH) {
            throw InvalidParamsException("Parameter 'filename' is longer than $MAX_FILENAME_LENGTH characters")
        }
        if (!SAFE_FILENAME.matches(name)) {
            throw InvalidParamsException(
                "Parameter 'filename' must be a bare filename of A-Z a-z 0-9 . _ - " +
                    "(use 'path' to choose a directory), got '$name'",
            )
        }
        return if (name.substringAfterLast('.', "").lowercase() in JPEG_EXTENSIONS) name else "$name.jpg"
    }

    /**
     * A short, opaque, URL-safe identifier for a stored photo.
     *
     * Opaque rather than sequential so that holding one download URL tells a client nothing about
     * the others, and URL-safe because it goes straight into a path segment.
     */
    fun newId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
