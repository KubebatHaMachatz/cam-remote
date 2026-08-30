package com.camremote.app.adapter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.camremote.core.logic.PhotoIndex
import com.camremote.core.logic.PhotoNaming
import com.camremote.core.port.OpenPhoto
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import java.io.File
import java.io.IOException

/**
 * Saves captures into the user's own `Documents` folder through MediaStore, and serves them back.
 *
 * **Why MediaStore, and why no storage permission.** Under scoped storage an app may create files
 * it owns anywhere in shared storage without holding any storage permission at all — that has been
 * true since API 29, and this project's `minSdk` is 29 precisely so the rule holds unconditionally.
 * `WRITE_EXTERNAL_STORAGE` is not declared, is not requested, and would be a no-op on these
 * devices anyway. The only permission `camera.capture` needs is `CAMERA`.
 *
 * **Why two writes.** The camera writes a JPEG to a private scratch file first, and only a
 * completed capture is copied into `Documents`. Publishing first would leave a torn file visible in
 * the user's file manager whenever the sensor failed, and MediaStore offers no destination that can
 * be rolled back once CameraX has begun writing to it. The scratch copy costs a few milliseconds
 * and is deleted on every path out of [publish].
 *
 * **What is deliberately not here.** Where photos may go and what they may be called are decisions,
 * so they live in `:core` — `PhotoPaths` and `PhotoNaming` — and are tested on a desktop JVM. So is
 * the index. What is left is I/O against a `ContentResolver`, which can only run on a device, and
 * is covered by the instrumented test.
 */
class MediaStorePhotoStore(
    private val context: Context,
    private val scratchDirectory: File,
    indexFile: File,
) : PhotoStore {

    private val index = PhotoIndex(indexFile)

    /**
     * The `Files` collection rather than `Images`.
     *
     * `MediaStore.Images` only accepts a `RELATIVE_PATH` under `DCIM/` or `Pictures/` and throws
     * `IllegalArgumentException` for anything else, so it cannot reach `Documents` at all.
     */
    private val collection: Uri
        get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    init {
        scratchDirectory.mkdirs()
        // A scratch file surviving startup means a previous process died mid-capture. Nothing can
        // want it now, and leaving it would slowly fill the cache directory.
        scratchDirectory.listFiles()?.forEach { it.delete() }
        // Photos in shared storage outlive the app and the user is free to delete them, so an id
        // is only kept if the row it names is still there.
        index.load { exists(Uri.parse(it.uri)) }
    }

    /** A private path in the cache directory, invisible to the user and to the media scanner. */
    override fun scratchPathFor(filename: String): String {
        scratchDirectory.mkdirs()
        return File(scratchDirectory, filename).path
    }

    /** Copies a finished capture into shared storage and mints the id clients download it by. */
    override fun publish(
        scratchPath: String,
        relativeDirectory: String,
        filename: String,
        capturedAtMillis: Long,
    ): StoredPhoto {
        val source = File(scratchPath)
        if (!source.isFile) throw IOException("The camera reported success but wrote no file")

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // MediaStore creates the directory, so the agent never needs to make one itself.
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDirectory/")
            // Nothing sees a half-copied image: the row is hidden until the bytes are all there.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused a new file in $relativeDirectory")

        try {
            val copied = resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("MediaStore would not open $uri for writing")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )

            val stored = StoredPhoto(
                id = PhotoNaming.newId(),
                uri = uri.toString(),
                // Read back rather than assumed: MediaStore silently renames on a name collision,
                // so the operator would otherwise be told a filename that does not exist.
                displayPath = displayPath(uri) ?: "$relativeDirectory/$filename",
                sizeBytes = copied,
                capturedAtMillis = capturedAtMillis,
            )
            index.add(stored)
            return stored
        } catch (e: Exception) {
            // Leave nothing half-written in the user's Documents.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            source.delete()
        }
    }

    /** Throws away a scratch file after a capture that failed. */
    override fun discard(scratchPath: String) {
        runCatching { File(scratchPath).delete() }
    }

    /**
     * Opens a published photo for download.
     *
     * A known id whose file the user has since deleted reads as null, not as an exception on the
     * download route.
     */
    override fun open(id: String): OpenPhoto? {
        val stored = index.get(id) ?: return null
        val stream = runCatching { context.contentResolver.openInputStream(Uri.parse(stored.uri)) }
            .getOrNull() ?: return null
        return OpenPhoto(photo = stored, contentType = "image/jpeg", stream = stream)
    }

    /** Whether the MediaStore row still exists, without opening the file behind it. */
    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** The location a person would look in, as MediaStore actually recorded it. */
    private fun displayPath(uri: Uri): String? = runCatching {
        val columns = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val directory = cursor.getString(0)?.trimEnd('/') ?: return@use null
            val name = cursor.getString(1) ?: return@use null
            "$directory/$name"
        }
    }.getOrNull()
}
