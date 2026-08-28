package com.camremote.app.adapter

import com.camremote.core.logic.GetPropOutput
import com.camremote.core.port.PropertyReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Reads a property by running Android's own `getprop` binary.
 *
 * The primary implementation because it needs no permission, no root, and no hidden API — it is
 * simply what the platform ships. The key is passed as a discrete argument to the executable, so no
 * shell is involved and no quoting question arises (it is validated upstream regardless).
 *
 * This class is deliberately dumb: deciding what the output means lives in [GetPropOutput], where a
 * desktop JVM can test it.
 */
class ExecGetPropReader(
    private val binaries: List<String> = DEFAULT_BINARIES,
    private val timeoutSeconds: Long = 5,
) : PropertyReader {

    override fun read(key: String): String? {
        var lastFailure: Exception? = null
        for (binary in binaries) {
            try {
                return read(binary, key)
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("No getprop binary configured")
    }

    private fun read(binary: String, key: String): String? {
        val process = ProcessBuilder(binary, key).start()
        try {
            // Drain stdout before waiting: a process whose output fills the pipe buffer blocks
            // forever if the parent waits first.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw IOException("$binary did not finish within ${timeoutSeconds}s")
            }
            if (process.exitValue() != 0) {
                throw IOException("$binary exited with ${process.exitValue()}")
            }
            return GetPropOutput.parse(output)
        } finally {
            process.destroy()
        }
    }

    private companion object {
        /**
         * The absolute path first, since it is where every standard Android build puts it. The bare
         * name is a fallback for unusual builds -- ProcessBuilder resolves it through PATH -- which
         * costs nothing and saves a confusing failure on a custom ROM.
         */
        val DEFAULT_BINARIES = listOf("/system/bin/getprop", "getprop")
    }
}
