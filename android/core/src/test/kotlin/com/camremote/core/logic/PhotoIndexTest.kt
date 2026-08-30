package com.camremote.core.logic

import com.camremote.core.port.StoredPhoto
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The index is what makes a download id outlive the process that minted it, and photos now live in
 * shared storage where the user can delete them behind the agent's back. Both of those are edge
 * cases rather than happy paths, which is exactly why this logic sits in `:core` rather than inside
 * the Android store that needs a `ContentResolver`.
 */
class PhotoIndexTest {

    private val directory: File = Files.createTempDirectory("camremote-index").toFile()
    private val file = File(directory, "photo-index.jsonl")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun photo(id: String, name: String = "$id.jpg") = StoredPhoto(
        id = id,
        uri = "content://media/external/file/$id",
        displayPath = "Documents/cam-remote/$name",
        sizeBytes = 2048,
        capturedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `finds nothing before anything has been added`() {
        val index = PhotoIndex(file).apply { load { true } }

        assertNull(index.get("nope"))
        assertEquals(0, index.size)
    }

    @Test
    fun `loads cleanly when the file has never existed`() {
        PhotoIndex(file).load { true }

        assertEquals(false, file.exists())
    }

    @Test
    fun `returns a photo it has been given`() {
        val index = PhotoIndex(file).apply { load { true } }
        val stored = photo("abc")

        index.add(stored)

        assertEquals(stored, index.get("abc"))
        assertEquals(1, index.size)
    }

    @Test
    fun `still knows a photo after the process restarts`() {
        PhotoIndex(file).apply { load { true } }.add(photo("abc"))

        val reopened = PhotoIndex(file).apply { load { true } }

        assertEquals("Documents/cam-remote/abc.jpg", reopened.get("abc")?.displayPath)
    }

    @Test
    fun `forgets a photo the user has deleted from shared storage`() {
        val index = PhotoIndex(file).apply { load { true } }
        index.add(photo("kept"))
        index.add(photo("deleted"))

        val reopened = PhotoIndex(file).apply { load { it.id != "deleted" } }

        assertEquals("Documents/cam-remote/kept.jpg", reopened.get("kept")?.displayPath)
        assertNull(reopened.get("deleted"))
    }

    @Test
    fun `compacts the file so entries for deleted photos do not accumulate`() {
        val index = PhotoIndex(file).apply { load { true } }
        index.add(photo("kept"))
        index.add(photo("deleted"))

        PhotoIndex(file).load { it.id != "deleted" }

        assertEquals(1, file.readLines().count { it.isNotBlank() })
    }

    @Test
    fun `leaves the file alone when every entry survives`() {
        val index = PhotoIndex(file).apply { load { true } }
        index.add(photo("a"))
        index.add(photo("b"))
        val before = file.readText()

        PhotoIndex(file).load { true }

        assertEquals(before, file.readText())
    }

    @Test
    fun `survives a half-written line left by a process that died mid-append`() {
        val index = PhotoIndex(file).apply { load { true } }
        index.add(photo("good"))
        file.appendText("{\"id\":\"truncated\",\"uri\":\"cont")

        val reopened = PhotoIndex(file).apply { load { true } }

        assertEquals(1, reopened.size)
        assertEquals("Documents/cam-remote/good.jpg", reopened.get("good")?.displayPath)
    }

    @Test
    fun `drops the unreadable line from the file rather than re-reading it forever`() {
        PhotoIndex(file).apply { load { true } }.add(photo("good"))
        file.appendText("not json at all\n")

        PhotoIndex(file).load { true }

        assertEquals(1, file.readLines().count { it.isNotBlank() })
    }

    @Test
    fun `keeps the newest entry when an id somehow repeats`() {
        val index = PhotoIndex(file).apply { load { true } }
        index.add(photo("abc", name = "first.jpg"))
        index.add(photo("abc", name = "second.jpg"))

        assertEquals("Documents/cam-remote/second.jpg", index.get("abc")?.displayPath)
        assertEquals(1, index.size)
    }
}
