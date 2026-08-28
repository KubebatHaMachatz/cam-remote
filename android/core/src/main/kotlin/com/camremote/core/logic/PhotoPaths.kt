package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException
import java.io.File

/**
 * Decides where a capture may be written.
 *
 * `camera.capture` takes a destination directory, so a path arrives over the network and is used to
 * create a file. Confining it to an explicit set of roots is the entire security story for that
 * parameter, and it is done by canonicalising first — a textual prefix check would be fooled by
 * `..`, by a symlink, and by a sibling directory whose name merely starts with the root's.
 */
object PhotoPaths {

    /**
     * @param defaultRoot where photos go when the caller expresses no preference; also the base for
     *   relative directories.
     * @param allowedRoots every location this agent is willing to write to.
     * @throws InvalidParamsException if the requested directory escapes [allowedRoots].
     */
    fun resolve(
        defaultRoot: File,
        allowedRoots: List<File>,
        requestedDirectory: String?,
        filename: String,
    ): File {
        require(allowedRoots.isNotEmpty()) { "At least one allowed root is required" }

        val requested = when {
            requestedDirectory.isNullOrBlank() -> defaultRoot
            File(requestedDirectory).isAbsolute -> File(requestedDirectory)
            else -> File(defaultRoot, requestedDirectory)
        }

        val canonical = requested.canonicalFile
        val permitted = allowedRoots.map { it.canonicalFile }.any { canonical.isWithin(it) }
        if (!permitted) {
            throw InvalidParamsException(
                "Parameter 'path' must be inside one of: " +
                    allowedRoots.joinToString { it.canonicalPath } +
                    ", got '${canonical.path}'",
            )
        }

        return File(canonical, filename)
    }

    /** True when this file is the given root or sits underneath it, comparing whole segments. */
    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path + File.separator)
}
