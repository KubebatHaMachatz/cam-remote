package com.camremote.core.logic

import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.ProtocolJson
import java.io.File

/**
 * Remembers which photos the agent has published, so a download id keeps working.
 *
 * A foreground service is long-lived but not immortal: a URL handed to the control machine should
 * survive Android restarting the process, so the index is written to disk as JSON lines rather than
 * held in memory. Appending one line per photo keeps a capture's cost constant.
 *
 * This lives in `:core`, away from the store that uses it, for a practical reason: once photos are
 * addressed by content URI the store itself needs a `ContentResolver` and can only be exercised on
 * a device. The bookkeeping around it does not, and it is the part with edge cases worth testing —
 * a truncated line, an entry whose photo has since been deleted, a file that has never existed.
 */
class PhotoIndex(private val file: File) {

    private val entries = LinkedHashMap<String, StoredPhoto>()

    /**
     * Reads the index, dropping entries [isLive] no longer recognises, and compacts what is left.
     *
     * Photos in shared storage outlive the app, so a user is free to delete one from a file
     * manager; the index has to tolerate that rather than serve an id that cannot be opened.
     * Compacting on load is what keeps the file from growing forever as photos come and go.
     */
    @Synchronized
    fun load(isLive: (StoredPhoto) -> Boolean) {
        entries.clear()
        if (!file.isFile) return

        val lines = file.readLines().filter { it.isNotBlank() }
        // A line that will not parse is a half-written append from a process that died mid-write.
        // Dropping it silently is right: one lost download id is not worth failing to start over.
        val surviving = lines.mapNotNull { line ->
            runCatching { ProtocolJson.json.decodeFromString(StoredPhoto.serializer(), line) }
                .getOrNull()
        }.filter(isLive)

        surviving.forEach { entries[it.id] = it }
        if (surviving.size != lines.size) {
            file.writeText(surviving.joinToString(separator = "") { encode(it) })
        }
    }

    /** Records a photo and appends it, so its id survives the service being restarted. */
    @Synchronized
    fun add(photo: StoredPhoto) {
        entries[photo.id] = photo
        file.parentFile?.mkdirs()
        file.appendText(encode(photo))
    }

    /** The photo with this id, or null if the agent has never published one. */
    @Synchronized
    fun get(id: String): StoredPhoto? = entries[id]

    /** How many photos the index is currently tracking. */
    @get:Synchronized
    val size: Int get() = entries.size

    /** One index line: the photo as JSON, newline-terminated. */
    private fun encode(photo: StoredPhoto): String =
        ProtocolJson.json.encodeToString(StoredPhoto.serializer(), photo) + "\n"
}
