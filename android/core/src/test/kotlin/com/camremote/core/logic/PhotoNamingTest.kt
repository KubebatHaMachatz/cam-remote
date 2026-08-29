package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Naming captures, and minting the ids they are downloaded by.
 *
 * The filename comes partly from the network, so the cases that matter are the ones where a
 * caller supplies something awkward: a path separator, a control character, or 300 characters.
 */
class PhotoNamingTest {

    /** 2023-11-14T22:13:19.123Z */
    private val clock = FakeClock(1_699_999_999_123)

    @Test
    fun `generates a sortable UTC filename when none is given`() {
        val name = PhotoNaming.filenameFor(clock, requested = null)

        // UTC rather than device-local: the control machine and the handset are often in different
        // zones, and a filename that sorts chronologically is worth more than a familiar one.
        assertEquals("camremote-20231114-221319-123.jpg", name)
    }

    @Test
    fun `keeps a caller-supplied name`() {
        assertEquals("front-door.jpg", PhotoNaming.filenameFor(clock, requested = "front-door.jpg"))
    }

    @Test
    fun `appends the extension when the caller omits it`() {
        assertEquals("front-door.jpg", PhotoNaming.filenameFor(clock, requested = "front-door"))
    }

    @Test
    fun `accepts jpeg as an extension without doubling it`() {
        assertEquals("front-door.jpeg", PhotoNaming.filenameFor(clock, requested = "front-door.jpeg"))
    }

    @Test
    fun `rejects a name containing a path separator`() {
        // 'filename' names a file. Choosing a directory is what the 'path' parameter is for, and it
        // is validated separately against the allowed roots.
        listOf("../escape.jpg", "sub/dir.jpg", "sub\\dir.jpg", "/absolute.jpg").forEach {
            assertFailsWith<InvalidParamsException>("expected '$it' to be rejected") {
                PhotoNaming.filenameFor(clock, requested = it)
            }
        }
    }

    @Test
    fun `rejects a blank name`() {
        assertFailsWith<InvalidParamsException> { PhotoNaming.filenameFor(clock, requested = "   ") }
    }

    @Test
    fun `rejects characters that make a filename awkward to handle`() {
        listOf("a b.jpg", "quote\".jpg", "star*.jpg").forEach {
            assertFailsWith<InvalidParamsException>("expected '$it' to be rejected") {
                PhotoNaming.filenameFor(clock, requested = it)
            }
        }
    }

    @Test
    fun `rejects an absurdly long name`() {
        assertFailsWith<InvalidParamsException> {
            PhotoNaming.filenameFor(clock, requested = "x".repeat(300) + ".jpg")
        }
    }

    @Test
    fun `generates opaque ids that do not collide`() {
        val ids = (1..500).map { PhotoNaming.newId() }

        assertEquals(500, ids.toSet().size)
        // The id appears in a URL path, so it stays inside an unreserved character set.
        assertTrue(ids.all { it.matches(Regex("^[A-Za-z0-9_-]{8,}$")) })
    }
}
