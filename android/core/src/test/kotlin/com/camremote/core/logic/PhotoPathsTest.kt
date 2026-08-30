package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `camera.capture` accepts a destination directory, which means a directory name arrives from the
 * network and is used to create a folder in the user's own `Documents`. MediaStore rejects an
 * escape attempt of its own accord, but relying on that would leave the agent's contract undefined,
 * so the rule is settled here where it can be stated and tested precisely.
 */
class PhotoPathsTest {

    @Test
    fun `defaults to the agent's own folder under Documents`() {
        assertEquals("Documents/cam-remote", PhotoPaths.resolveRelativeDirectory(null))
    }

    @Test
    fun `treats a blank directory as no directory at all`() {
        listOf("", "   ").forEach {
            assertEquals("Documents/cam-remote", PhotoPaths.resolveRelativeDirectory(it))
        }
    }

    @Test
    fun `places a requested directory under Documents`() {
        assertEquals("Documents/reports", PhotoPaths.resolveRelativeDirectory("reports"))
    }

    @Test
    fun `accepts nested directories`() {
        assertEquals(
            "Documents/cam-remote/2026-08-30",
            PhotoPaths.resolveRelativeDirectory("cam-remote/2026-08-30"),
        )
    }

    @Test
    fun `accepts a redundant Documents prefix rather than doubling it`() {
        // 'Documents/reports' and 'reports' name the same place; a caller who spells out the
        // primary directory should not end up in Documents/Documents/reports.
        assertEquals("Documents/reports", PhotoPaths.resolveRelativeDirectory("Documents/reports"))
    }

    @Test
    fun `allows Documents itself`() {
        listOf("Documents", "Documents/").forEach {
            assertEquals("Documents", PhotoPaths.resolveRelativeDirectory(it))
        }
    }

    @Test
    fun `tolerates a trailing slash`() {
        assertEquals("Documents/reports", PhotoPaths.resolveRelativeDirectory("reports/"))
    }

    @Test
    fun `rejects an absolute path`() {
        listOf("/sdcard/Documents", "/etc", "/").forEach { attempt ->
            assertFailsWith<InvalidParamsException>("expected '$attempt' to be rejected") {
                PhotoPaths.resolveRelativeDirectory(attempt)
            }
        }
    }

    @Test
    fun `rejects traversal out of Documents`() {
        listOf("..", "../..", "reports/../../elsewhere", "Documents/../Download").forEach { attempt ->
            assertFailsWith<InvalidParamsException>("expected '$attempt' to be rejected") {
                PhotoPaths.resolveRelativeDirectory(attempt)
            }
        }
    }

    @Test
    fun `rejects a single dot segment`() {
        assertFailsWith<InvalidParamsException> { PhotoPaths.resolveRelativeDirectory("reports/./x") }
    }

    @Test
    fun `rejects an empty directory name`() {
        assertFailsWith<InvalidParamsException> { PhotoPaths.resolveRelativeDirectory("reports//x") }
    }

    @Test
    fun `rejects a windows separator rather than silently treating it as a name`() {
        assertFailsWith<InvalidParamsException> { PhotoPaths.resolveRelativeDirectory("reports\\x") }
    }

    @Test
    fun `rejects characters that are not valid in a directory name`() {
        listOf("rep:orts", "rep*orts", "rep?orts", "rep\"orts", "rep<orts", "rep|orts", "rep\norts")
            .forEach { attempt ->
                assertFailsWith<InvalidParamsException>("expected '$attempt' to be rejected") {
                    PhotoPaths.resolveRelativeDirectory(attempt)
                }
            }
    }

    @Test
    fun `trims whitespace around the whole parameter`() {
        assertEquals("Documents/reports", PhotoPaths.resolveRelativeDirectory("  reports  "))
    }

    @Test
    fun `rejects a directory name padded with spaces`() {
        // Padding is invisible in a file manager and is stripped by some filesystems, so two
        // captures could silently disagree about where they went. Whitespace around the parameter
        // as a whole is only noise and is trimmed above; this is about a name in the middle of it.
        listOf("reports/ x", "reports/x /y").forEach { attempt ->
            assertFailsWith<InvalidParamsException>("expected '$attempt' to be rejected") {
                PhotoPaths.resolveRelativeDirectory(attempt)
            }
        }
    }

    @Test
    fun `rejects an unreasonably long directory name`() {
        assertFailsWith<InvalidParamsException> {
            PhotoPaths.resolveRelativeDirectory("a".repeat(200))
        }
    }

    @Test
    fun `rejects an unreasonably deep directory`() {
        assertFailsWith<InvalidParamsException> {
            PhotoPaths.resolveRelativeDirectory((1..12).joinToString("/") { "d$it" })
        }
    }
}
