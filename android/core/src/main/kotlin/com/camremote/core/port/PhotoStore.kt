package com.camremote.core.port

import java.io.InputStream
import kotlinx.serialization.Serializable

/**
 * A photo the agent has taken and can still serve.
 *
 * [uri] is opaque to `:core` — only the store understands it — while [displayPath] is the location
 * a human would look in, such as `Documents/cam-remote/camremote-20260830-120000-000.jpg`. The two
 * are separate because the first is how the agent reopens the photo and the second is what the
 * operator is told; conflating them worked only while both happened to be a filesystem path.
 *
 * Serializable because the store persists its index across process restarts: a download id handed
 * to a client should not stop working because Android restarted the service.
 */
@Serializable
data class StoredPhoto(
    val id: String,
    val uri: String,
    val displayPath: String,
    val sizeBytes: Long,
    val capturedAtMillis: Long,
)

/** A stored photo opened for download. The caller owns [stream] and must close it. */
data class OpenPhoto(
    val photo: StoredPhoto,
    val contentType: String,
    val stream: InputStream,
)

/**
 * Decides where photos live and hands them back out again.
 *
 * Captures are written twice on purpose. The camera writes a JPEG to a private scratch file, and
 * only once that has succeeded is it published into the user's shared storage. Publishing first
 * would leave a half-written file visible in a file manager if the sensor failed, and there is no
 * way to hand CameraX a destination that can be rolled back.
 *
 * The download half exists because a location on the handset is of no use to the control machine;
 * without it, "save the image to a specified location" would leave the operator with a string
 * rather than a photograph.
 */
interface PhotoStore {

    /** A private path the camera can write a JPEG to. Never visible to the user. */
    fun scratchPathFor(filename: String): String

    /**
     * Moves a captured scratch file into shared storage and mints the id clients download it by.
     *
     * The scratch file is consumed either way. [relativeDirectory] has already been validated by
     * [com.camremote.core.logic.PhotoPaths]; [filename] by
     * [com.camremote.core.logic.PhotoNaming].
     *
     * @throws Exception if the photo could not be published, in which case nothing is left behind.
     */
    fun publish(
        scratchPath: String,
        relativeDirectory: String,
        filename: String,
        capturedAtMillis: Long,
    ): StoredPhoto

    /** Throws away a scratch file after a capture that failed. */
    fun discard(scratchPath: String)

    /** Opens a previously published photo, or null when the id is unknown or the file has gone. */
    fun open(id: String): OpenPhoto?
}
