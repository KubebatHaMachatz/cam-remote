package com.camremote.core.logic

import com.camremote.core.port.PropertyReader
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Two ways of reading a property exist and neither works everywhere: spawning `getprop` can be
 * blocked by SELinux policy on some builds, and reflecting on `android.os.SystemProperties` is
 * blocked by hidden-API restrictions on others. Trying them in order is a decision, so it is made
 * in code that can be tested rather than inside either adapter.
 */
class FirstAvailablePropertyReaderTest {

    @Test
    fun `uses the first reader when it works`() {
        val reader = FirstAvailablePropertyReader(
            listOf(
                PropertyReader { "from-primary" },
                PropertyReader { throw AssertionError("must not be consulted") },
            ),
        )

        assertEquals("from-primary", reader.read("ro.product.model"))
    }

    @Test
    fun `falls through to the next reader when the first fails`() {
        val reader = FirstAvailablePropertyReader(
            listOf(
                PropertyReader { throw IOException("getprop blocked by policy") },
                PropertyReader { "from-fallback" },
            ),
        )

        assertEquals("from-fallback", reader.read("ro.product.model"))
    }

    @Test
    fun `an unset property is an answer, not a failure`() {
        var fallbackConsulted = false
        val reader = FirstAvailablePropertyReader(
            listOf(
                PropertyReader { null },
                PropertyReader { fallbackConsulted = true; "should-not-be-used" },
            ),
        )

        // Falling through on null would turn "this property is not set" into a slow lie.
        assertNull(reader.read("ro.not.set"))
        assertEquals(false, fallbackConsulted)
    }

    @Test
    fun `rethrows the last failure when every reader fails`() {
        val reader = FirstAvailablePropertyReader(
            listOf(
                PropertyReader { throw IOException("first failed") },
                PropertyReader { throw IOException("second failed") },
            ),
        )

        val error = assertFailsWith<IOException> { reader.read("ro.product.model") }
        assertEquals("second failed", error.message)
    }

    @Test
    fun `requires at least one reader`() {
        assertFailsWith<IllegalArgumentException> { FirstAvailablePropertyReader(emptyList()) }
    }
}
