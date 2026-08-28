package com.camremote.app.adapter

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Copies a capture into the device gallery via MediaStore.
 *
 * Optional, and off by default: captures live in the app's own external directory, which needs no
 * storage permission on API 29+ and keeps the user's camera roll free of images they did not take
 * themselves. Turning it on is a per-request choice, so the operator decides.
 */
class MediaStoreGalleryPublisher(private val context: Context) : GalleryPublisher {

    override fun publish(file: File): String? {
        if (!file.isFile) return null

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/cam-remote")
                // Marked pending until the bytes are written, so nothing sees a half-copied image.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        return runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: return@runCatching null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri.toString()
        }.getOrElse {
            // A failed gallery copy must not fail the capture: the photo is already safely on disk.
            resolver.delete(uri, null, null)
            null
        }
    }
}
