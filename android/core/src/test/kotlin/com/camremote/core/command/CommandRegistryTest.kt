package com.camremote.core.command

import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.testing.TestCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The registry is the extension point: adding a capability means adding one entry here.
 * These tests cover lookup, the duplicate-name mistake that a hand-written list invites, and
 * the stable catalog ordering clients rely on.
 */
class CommandRegistryTest {

    @Test
    fun `finds a registered command by name`() {
        val command = TestCommand(name = "system.status") { Success(null) }
        val registry = CommandRegistry(listOf(command))

        assertEquals(command, registry["system.status"])
    }

    @Test
    fun `returns null for an unregistered name`() {
        val registry = CommandRegistry(listOf(TestCommand(name = "system.status") { Success(null) }))

        assertNull(registry["camera.open"])
    }

    @Test
    fun `rejects duplicate command names at construction`() {
        // Registration is a hand-written list, so the one mistake it invites is registering the same
        // name twice. Failing loudly at startup beats one command silently shadowing another.
        val error = assertFailsWith<IllegalArgumentException> {
            CommandRegistry(
                listOf(
                    TestCommand(name = "system.status") { Success(null) },
                    TestCommand(name = "system.status") { Success(null) },
                ),
            )
        }

        assertEquals(true, error.message?.contains("system.status"))
    }

    @Test
    fun `exposes descriptors sorted by name for a stable catalog`() {
        val registry = CommandRegistry(
            listOf(
                TestCommand(name = "system.status") { Success(null) },
                TestCommand(name = "camera.open") { Success(null) },
                TestCommand(name = "device.getprop") { Success(null) },
            ),
        )

        assertEquals(
            listOf("camera.open", "device.getprop", "system.status"),
            registry.descriptors().map { it.name },
        )
    }
}
