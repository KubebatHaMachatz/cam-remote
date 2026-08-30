package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException

/**
 * Decides which shared-storage directory a capture is written to.
 *
 * Captures land in the user's own `Documents`, reachable from any file manager, rather than in the
 * app's private storage where only the agent could see them. That is what makes "save it to a
 * specified location" mean something to the person holding the phone.
 *
 * `camera.capture` takes a destination directory, so a directory name arrives over the network and
 * becomes a folder. MediaStore refuses to create anything outside the collection's primary
 * directory on its own account, but leaning on that would leave the agent's own contract undefined
 * and its error messages down to whatever the platform happened to throw. The rule is therefore
 * settled here, in `:core`, where it is one pure function and is tested exhaustively.
 */
object PhotoPaths {

    /**
     * The MediaStore primary directory captures live under.
     *
     * `Documents` rather than `Pictures`: these are files an operator asked a remote agent to
     * produce and will go looking for deliberately, not snapshots that belong in a camera roll.
     */
    const val PRIMARY_DIRECTORY = "Documents"

    /** Where captures go when the caller expresses no preference. */
    const val DEFAULT_SUBDIRECTORY = "cam-remote"

    private val SAFE_SEGMENT = Regex("^[A-Za-z0-9 ._-]+$")
    private const val MAX_SEGMENT_LENGTH = 64
    private const val MAX_DEPTH = 8

    /**
     * Returns a MediaStore `RELATIVE_PATH` directory such as `Documents/cam-remote`.
     *
     * [requested] is always interpreted relative to [PRIMARY_DIRECTORY]; there is deliberately no
     * way to name a different one, so no caller can write outside `Documents`.
     *
     * @throws InvalidParamsException if [requested] is absolute, escapes upwards, or contains a
     *   directory name the agent is unwilling to create.
     */
    fun resolveRelativeDirectory(requested: String?): String {
        val raw = requested?.trim().orEmpty()
        if (raw.isEmpty()) return "$PRIMARY_DIRECTORY/$DEFAULT_SUBDIRECTORY"

        if (raw.startsWith("/")) {
            throw InvalidParamsException(
                "Parameter 'path' is a directory inside '$PRIMARY_DIRECTORY', not an absolute path, " +
                    "got '$raw'",
            )
        }
        if (raw.contains('\\')) {
            throw InvalidParamsException(
                "Parameter 'path' must separate directories with '/', got '$raw'",
            )
        }

        // 'Documents/reports' and 'reports' name the same place. Accepting both spellings is
        // friendlier than rejecting one, and it stops a caller landing in Documents/Documents.
        val relative = raw.removeSuffix("/")
            .let { if (it == PRIMARY_DIRECTORY) "" else it.removePrefix("$PRIMARY_DIRECTORY/") }
        if (relative.isEmpty()) return PRIMARY_DIRECTORY

        val segments = relative.split('/')
        if (segments.size > MAX_DEPTH) {
            throw InvalidParamsException(
                "Parameter 'path' must be at most $MAX_DEPTH directories deep, got '$raw'",
            )
        }
        segments.forEach { validate(it, raw) }

        return "$PRIMARY_DIRECTORY/${segments.joinToString("/")}"
    }

    /** Everything one directory name has to be for the agent to create it. */
    private fun validate(segment: String, raw: String) {
        val problem = when {
            segment.isEmpty() -> "must not contain an empty directory name"
            segment == "." || segment == ".." -> "must not contain '.' or '..'"
            segment.length > MAX_SEGMENT_LENGTH ->
                "must use directory names of at most $MAX_SEGMENT_LENGTH characters"
            !SAFE_SEGMENT.matches(segment) ->
                "must use directory names of A-Z a-z 0-9 space . _ -"
            // Invisible in a file manager, and silently stripped by some filesystems, which would
            // leave two captures disagreeing about where they went.
            segment != segment.trim() -> "must not pad a directory name with spaces"
            else -> return
        }
        throw InvalidParamsException("Parameter 'path' $problem, got '$raw'")
    }
}
