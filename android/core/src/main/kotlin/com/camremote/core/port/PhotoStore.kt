package com.camremote.core.port

import java.io.InputStream
import kotlinx.serialization.Serializable

/**
 * A photo the agent has taken and can still serve.
 *
 * Serializable because the store persists its index across process restarts: a download id handed
 * to a client should not stop working because Android restarted the service.
 */
@Serializable
data class StoredPhoto(
    val id: String,
    val path: String,
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
 * The download half exists because a filesystem path on the handset is of no use to the control
 * machine; without it, "save the image to a specified location" would leave the operator with a
 * string rather than a photograph.
 */
interface PhotoStore {

    /**
     * Resolves the absolute path a new photo should be written to.
     *
     * @throws com.camremote.core.protocol.InvalidParamsException if [directory] is outside the
     *   locations this agent is willing to write to.
     */
    fun destinationFor(directory: String?, filename: String): String

    /**
     * Records a file the camera has just written, minting the id clients download it by.
     *
     * Separate from [destinationFor] because the size is only known once the bytes are on disk.
     */
    fun record(path: String, capturedAtMillis: Long): StoredPhoto

    /** Adds the photo to the device gallery, returning its MediaStore URI, or null if unavailable. */
    fun publish(photo: StoredPhoto): String?

    /** Opens a previously recorded photo, or null when the id is unknown. */
    fun open(id: String): OpenPhoto?
}
