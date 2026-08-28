package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `camera.capture` accepts a destination directory, which means a path arrives from the network and
 * is used to write a file. Confining it to a set of allowed roots is the whole security story for
 * that feature, so it is specified here in detail.
 */
class PhotoPathsTest {

    private val allowedRoot: File = Files.createTempDirectory("camremote-allowed").toFile()
    private val forbiddenRoot: File = Files.createTempDirectory("camremote-forbidden").toFile()

    @AfterTest
    fun cleanUp() {
        allowedRoot.deleteRecursively()
        forbiddenRoot.deleteRecursively()
    }

    @Test
    fun `falls back to the default root when no directory is requested`() {
        val resolved = PhotoPaths.resolve(
            defaultRoot = allowedRoot,
            allowedRoots = listOf(allowedRoot),
            requestedDirectory = null,
            filename = "a.jpg",
        )

        assertEquals(File(allowedRoot, "a.jpg").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `accepts an absolute directory inside an allowed root`() {
        val nested = File(allowedRoot, "captures/today")

        val resolved = PhotoPaths.resolve(
            defaultRoot = allowedRoot,
            allowedRoots = listOf(allowedRoot),
            requestedDirectory = nested.absolutePath,
            filename = "a.jpg",
        )

        assertEquals(File(nested, "a.jpg").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `treats a relative directory as relative to the default root`() {
        val resolved = PhotoPaths.resolve(
            defaultRoot = allowedRoot,
            allowedRoots = listOf(allowedRoot),
            requestedDirectory = "captures/today",
            filename = "a.jpg",
        )

        assertEquals(File(allowedRoot, "captures/today/a.jpg").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `rejects a directory outside every allowed root`() {
        assertFailsWith<InvalidParamsException> {
            PhotoPaths.resolve(
                defaultRoot = allowedRoot,
                allowedRoots = listOf(allowedRoot),
                requestedDirectory = forbiddenRoot.absolutePath,
                filename = "a.jpg",
            )
        }
    }

    @Test
    fun `rejects traversal out of an allowed root`() {
        listOf("..", "../..", "captures/../../elsewhere", allowedRoot.absolutePath + "/../escape")
            .forEach { attempt ->
                assertFailsWith<InvalidParamsException>("expected '$attempt' to be rejected") {
                    PhotoPaths.resolve(
                        defaultRoot = allowedRoot,
                        allowedRoots = listOf(allowedRoot),
                        requestedDirectory = attempt,
                        filename = "a.jpg",
                    )
                }
            }
    }

    @Test
    fun `rejects a symlink pointing outside the allowed roots`() {
        val link = File(allowedRoot, "sneaky")
        Files.createSymbolicLink(link.toPath(), forbiddenRoot.toPath())

        // Comparing textual prefixes would accept this; the check canonicalises first.
        assertFailsWith<InvalidParamsException> {
            PhotoPaths.resolve(
                defaultRoot = allowedRoot,
                allowedRoots = listOf(allowedRoot),
                requestedDirectory = link.absolutePath,
                filename = "a.jpg",
            )
        }
    }

    @Test
    fun `does not confuse a sibling directory sharing a name prefix`() {
        val sibling = File(allowedRoot.parentFile, allowedRoot.name + "-extra")
        sibling.mkdirs()
        try {
            assertFailsWith<InvalidParamsException> {
                PhotoPaths.resolve(
                    defaultRoot = allowedRoot,
                    allowedRoots = listOf(allowedRoot),
                    requestedDirectory = sibling.absolutePath,
                    filename = "a.jpg",
                )
            }
        } finally {
            sibling.deleteRecursively()
        }
    }

    @Test
    fun `accepts a directory under any of several allowed roots`() {
        val resolved = PhotoPaths.resolve(
            defaultRoot = allowedRoot,
            allowedRoots = listOf(allowedRoot, forbiddenRoot),
            requestedDirectory = forbiddenRoot.absolutePath,
            filename = "a.jpg",
        )

        assertEquals(File(forbiddenRoot, "a.jpg").canonicalPath, resolved.canonicalPath)
    }
}
