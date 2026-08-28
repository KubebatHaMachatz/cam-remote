package com.camremote.app.adapter

import com.camremote.core.protocol.InvalidParamsException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store touches the filesystem and nothing else, so it runs perfectly well on a desktop JVM
 * against a temporary directory. Only the camera itself genuinely needs a handset.
 */
class FileSystemPhotoStoreTest {

    private val root: File = Files.createTempDirectory("camremote-store").toFile()
    private val outside: File = Files.createTempDirectory("camremote-outside").toFile()

    private var publishedFile: File? = null
    private val gallery = GalleryPublisher { file ->
        publishedFile = file
        "content://media/external/images/media/7"
    }

    private fun store() = FileSystemPhotoStore(
        defaultRoot = root,
        allowedRoots = listOf(root),
        gallery = gallery,
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun `resolves a destination inside the default root`() {
        val path = store().destinationFor(directory = null, filename = "a.jpg")

        assertEquals(File(root, "a.jpg").canonicalPath, File(path).canonicalPath)
    }

    @Test
    fun `creates the destination directory so the camera can write into it`() {
        val path = store().destinationFor(directory = "captures/today", filename = "a.jpg")

        assertTrue(File(path).parentFile!!.isDirectory)
    }

    @Test
    fun `refuses a destination outside the allowed roots`() {
        assertFailsWith<InvalidParamsException> {
            store().destinationFor(directory = outside.absolutePath, filename = "a.jpg")
        }
    }

    @Test
    fun `records a written file with its real size`() {
        val store = store()
        val path = store.destinationFor(null, "a.jpg")
        File(path).writeBytes(ByteArray(1234) { 7 })

        val stored = store.record(path, capturedAtMillis = 1_699_999_999_123)

        assertEquals(1234, stored.sizeBytes)
        assertEquals(1_699_999_999_123, stored.capturedAtMillis)
        assertTrue(stored.id.isNotBlank())
    }

    @Test
    fun `serves a recorded photo back as bytes`() {
        val store = store()
        val path = store.destinationFor(null, "a.jpg")
        File(path).writeBytes(byteArrayOf(1, 2, 3, 4))
        val stored = store.record(path, capturedAtMillis = 0)

        val opened = store.open(stored.id)

        assertEquals("image/jpeg", opened?.contentType)
        assertTrue(opened!!.stream.use { it.readBytes() }.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `returns nothing for an unknown id`() {
        assertNull(store().open("no-such-id"))
    }

    @Test
    fun `returns nothing when the file has been deleted underneath it`() {
        val store = store()
        val path = store.destinationFor(null, "a.jpg")
        File(path).writeBytes(byteArrayOf(1))
        val stored = store.record(path, capturedAtMillis = 0)

        File(path).delete()

        // A dangling index entry must read as "gone", not as a crash on the download route.
        assertNull(store.open(stored.id))
    }

    @Test
    fun `still serves photos after the process restarts`() {
        val first = store()
        val path = first.destinationFor(null, "a.jpg")
        File(path).writeBytes(byteArrayOf(9))
        val stored = first.record(path, capturedAtMillis = 0)

        // A foreground service is long-lived but not immortal; an id handed to a client should not
        // stop working because Android restarted the process.
        val second = store()

        assertEquals(path, second.open(stored.id)?.photo?.path)
    }

    @Test
    fun `drops index entries whose files have gone when reloading`() {
        val first = store()
        val path = first.destinationFor(null, "a.jpg")
        File(path).writeBytes(byteArrayOf(9))
        val stored = first.record(path, capturedAtMillis = 0)
        File(path).delete()

        assertNull(store().open(stored.id))
    }

    @Test
    fun `publishes to the gallery through its own port`() {
        val store = store()
        val path = store.destinationFor(null, "a.jpg")
        File(path).writeBytes(byteArrayOf(1))
        val stored = store.record(path, capturedAtMillis = 0)

        val uri = store.publish(stored)

        assertEquals("content://media/external/images/media/7", uri)
        assertEquals(File(path).canonicalPath, publishedFile?.canonicalPath)
    }
}
