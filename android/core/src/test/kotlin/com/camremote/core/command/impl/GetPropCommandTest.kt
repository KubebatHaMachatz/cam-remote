package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.port.PropertyReader
import com.camremote.core.protocol.CommandCategory
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The assignment's `getprop` requirement.
 *
 * Covers single and batched reads, the distinction between an unset property and an empty one,
 * and the parameter validation that keeps a network-supplied key from becoming anything else.
 */
class GetPropCommandTest {

    private val device = mapOf(
        "ro.product.model" to "Pixel 7",
        "ro.build.version.sdk" to "37",
    )
    private val command = GetPropCommand(PropertyReader { device[it] })

    @Test
    fun `reads a single property`() = runTest {
        val outcome = command.execute(Params.of("key" to "ro.product.model"))

        val properties = assertIs<Success>(outcome).data?.get("properties")?.jsonObject
        assertEquals(JsonPrimitive("Pixel 7"), properties?.get("ro.product.model"))
    }

    @Test
    fun `reads several properties in one round trip`() = runTest {
        val outcome = command.execute(
            Params(
                buildJsonObject {
                    put(
                        "keys",
                        buildJsonArray {
                            add(JsonPrimitive("ro.product.model"))
                            add(JsonPrimitive("ro.build.version.sdk"))
                        },
                    )
                },
            ),
        )

        // One request for many properties: a remote control link is the expensive part, not the read.
        val properties = assertIs<Success>(outcome).data?.get("properties")?.jsonObject
        assertEquals(JsonPrimitive("Pixel 7"), properties?.get("ro.product.model"))
        assertEquals(JsonPrimitive("37"), properties?.get("ro.build.version.sdk"))
    }

    @Test
    fun `reports an unset property as null rather than an empty string`() = runTest {
        val outcome = command.execute(Params.of("key" to "ro.not.set"))

        // getprop cannot tell those apart, but the client can act on it, so null is the honest answer.
        val properties = assertIs<Success>(outcome).data?.get("properties")?.jsonObject
        assertEquals(JsonNull, properties?.get("ro.not.set"))
    }

    @Test
    fun `requires a key or keys parameter`() = runTest {
        assertFailsWith<InvalidParamsException> { command.execute(Params.EMPTY) }
    }

    @Test
    fun `rejects an empty keys array`() = runTest {
        val params = Params(buildJsonObject { put("keys", buildJsonArray { }) })

        assertFailsWith<InvalidParamsException> { command.execute(params) }
    }

    @Test
    fun `rejects a key that is not a valid property name`() = runTest {
        val error = assertFailsWith<InvalidParamsException> {
            command.execute(Params.of("key" to "ro.product.model; reboot"))
        }

        assertTrue(error.message!!.contains("ro.product.model; reboot"))
    }

    @Test
    fun `refuses an unreasonable number of keys in one request`() = runTest {
        val params = Params(
            buildJsonObject {
                put("keys", buildJsonArray { repeat(200) { add(JsonPrimitive("ro.key$it")) } })
            },
        )

        assertFailsWith<InvalidParamsException> { command.execute(params) }
    }

    @Test
    fun `is free-threaded because reading a property blocks nothing`() {
        assertEquals(null, command.exclusiveResource)
    }

    @Test
    fun `is one of the agent's reasons for existing, not a diagnostic`() {
        assertEquals(CommandCategory.PRIMARY, command.descriptor.category)
    }

    @Test
    fun `advertises its parameters in the catalog`() {
        val parameters = command.descriptor.parameters.associateBy { it.name }

        assertEquals(setOf("key", "keys"), parameters.keys)
        assertEquals("device.getprop", command.descriptor.name)
    }

    @Test
    fun `surfaces a reader failure as a device error`() = runTest {
        val failing = GetPropCommand(PropertyReader { throw java.io.IOException("getprop not found") })

        val outcome = failing.execute(Params.of("key" to "ro.product.model"))

        // An unreadable property store is the device's problem, not a bug in the agent.
        val error = assertIs<com.camremote.core.command.CommandOutcome.Failure>(outcome).error
        assertEquals(com.camremote.core.protocol.ErrorCode.DEVICE_ERROR, error.code)
    }

    @Test
    fun `returns an empty map for a JsonObject payload shape check`() = runTest {
        val outcome = command.execute(Params.of("key" to "ro.product.model"))

        assertIs<JsonObject>(assertIs<Success>(outcome).data?.get("properties"))
    }
}
