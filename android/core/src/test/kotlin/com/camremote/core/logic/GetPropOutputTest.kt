package com.camremote.core.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `getprop` reports an unset property as an empty line, which is indistinguishable from a property
 * set to the empty string. Deciding what that means is a judgement, so it lives here where it can
 * be tested, rather than inside the process-spawning adapter where it cannot.
 */
class GetPropOutputTest {

    @Test
    fun `reads a plain value`() {
        assertEquals("Pixel 7", GetPropOutput.parse("Pixel 7\n"))
    }

    @Test
    fun `tolerates a missing trailing newline`() {
        assertEquals("Pixel 7", GetPropOutput.parse("Pixel 7"))
    }

    @Test
    fun `tolerates carriage returns`() {
        assertEquals("Pixel 7", GetPropOutput.parse("Pixel 7\r\n"))
    }

    @Test
    fun `treats empty output as an unset property`() {
        assertNull(GetPropOutput.parse(""))
        assertNull(GetPropOutput.parse("\n"))
        assertNull(GetPropOutput.parse("   \n"))
    }

    @Test
    fun `keeps internal spacing in a value`() {
        assertEquals("Some Vendor  Inc.", GetPropOutput.parse("  Some Vendor  Inc.  \n"))
    }
}
