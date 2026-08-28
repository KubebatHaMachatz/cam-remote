package com.camremote.app.adapter

import com.camremote.core.logic.PhotoNaming
import com.camremote.core.logic.PhotoPaths
import com.camremote.core.port.OpenPhoto
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.ProtocolJson
import java.io.File

/** Adds a file to the device gallery. Separated out so the store itself needs no Android context. */
fun interface GalleryPublisher {
    fun publish(file: File): String?
}

/**
 * Stores captures on the filesystem and serves them back by id.
 *
 * All the decisions — which directories are writable, what a file is called, what an id looks like —
 * belong to `:core` and are tested there. What is left here is file I/O and a small index.
 *
 * The index is persisted as JSON lines rather than held only in memory, because a foreground service
 * is long-lived but not immortal: a download URL handed to the control machine should keep working
 * after Android restarts the process. Entries whose files have since disappeared are dropped on load,
 * which keeps the file from growing without bound as photos are deleted.
 */
class FileSystemPhotoStore(
    private val defaultRoot: File,
    private val allowedRoots: List<File>,
    private val gallery: GalleryPublisher,
    private val indexFile: File = File(defaultRoot, INDEX_FILENAME),
) : PhotoStore {

    private val index = LinkedHashMap<String, StoredPhoto>()

    init {
        defaultRoot.mkdirs()
        loadIndex()
    }

    override fun destinationFor(directory: String?, filename: String): String {
        val destination = PhotoPaths.resolve(
            defaultRoot = defaultRoot,
            allowedRoots = allowedRoots,
            requestedDirectory = directory,
            filename = filename,
        )
        // The camera writes straight to this path, so the directory has to exist before it starts.
        destination.parentFile?.mkdirs()
        return destination.path
    }

    override fun record(path: String, capturedAtMillis: Long): StoredPhoto {
        val file = File(path)
        val stored = StoredPhoto(
            id = PhotoNaming.newId(),
            path = file.path,
            sizeBytes = file.length(),
            capturedAtMillis = capturedAtMillis,
        )
        synchronized(index) {
            index[stored.id] = stored
            appendToIndex(stored)
        }
        return stored
    }

    override fun publish(photo: StoredPhoto): String? = gallery.publish(File(photo.path))

    override fun open(id: String): OpenPhoto? {
        val stored = synchronized(index) { index[id] } ?: return null
        val file = File(stored.path)
        if (!file.isFile) return null
        return OpenPhoto(
            photo = stored.copy(sizeBytes = file.length()),
            contentType = "image/jpeg",
            stream = file.inputStream(),
        )
    }

    private fun loadIndex() {
        if (!indexFile.isFile) return
        val surviving = indexFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { ProtocolJson.json.decodeFromString(StoredPhoto.serializer(), line) }
                    .getOrNull()
            }
            .filter { File(it.path).isFile }

        synchronized(index) {
            surviving.forEach { index[it.id] = it }
            // Rewrite compacted, so entries for deleted photos do not accumulate forever.
            if (surviving.size != indexFile.readLines().count { it.isNotBlank() }) {
                indexFile.writeText(
                    surviving.joinToString(separator = "") { encode(it) },
                )
            }
        }
    }

    private fun appendToIndex(stored: StoredPhoto) {
        indexFile.parentFile?.mkdirs()
        indexFile.appendText(encode(stored))
    }

    private fun encode(stored: StoredPhoto): String =
        ProtocolJson.json.encodeToString(StoredPhoto.serializer(), stored) + "\n"

    companion object {
        const val INDEX_FILENAME = "photo-index.jsonl"
    }
}
