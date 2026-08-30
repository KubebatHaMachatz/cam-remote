package com.camremote.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The wire format is the contract between the Android agent and the Python control application.
 * These tests pin it down before any of it exists, because both sides are written against it
 * independently and a silent change here breaks a client we cannot recompile.
 */
class ProtocolJsonTest {

    @Test
    fun `decodes a minimal request`() {
        val request = ProtocolJson.decodeRequest("""{"id":"r1","command":"system.status"}""")

        assertEquals("r1", request.id)
        assertEquals("system.status", request.command)
        assertEquals(Params.EMPTY, request.params)
    }

    @Test
    fun `decodes a request with typed params`() {
        val request = ProtocolJson.decodeRequest(
            """{"id":"r2","command":"camera.capture","params":{"filename":"a.jpg","jpegQuality":80}}""",
        )

        assertEquals("a.jpg", request.params.optString("filename"))
        assertEquals(80, request.params.optInt("jpegQuality", default = 95))
    }

    @Test
    fun `tolerates unknown fields so an older agent survives a newer client`() {
        val request = ProtocolJson.decodeRequest(
            """{"id":"r3","command":"system.status","futureField":true}""",
        )

        assertEquals("r3", request.id)
    }

    @Test
    fun `rejects malformed json with a typed protocol error`() {
        assertFailsWith<MalformedRequestException> {
            ProtocolJson.decodeRequest("{ not json")
        }
    }

    @Test
    fun `rejects a request that is missing the command field`() {
        assertFailsWith<MalformedRequestException> {
            ProtocolJson.decodeRequest("""{"id":"r4"}""")
        }
    }

    @Test
    fun `encodes a success response without an error field`() {
        val response = CommandResponse.ok(
            id = "r5",
            command = "device.getprop",
            data = buildJsonObject { put("ro.product.model", JsonPrimitive("Pixel")) },
            durationMs = 12,
        )

        val encoded = ProtocolJson.encodeResponse(response)

        assertEquals(
            """{"id":"r5","command":"device.getprop","status":"OK",""" +
                """"data":{"ro.product.model":"Pixel"},"durationMs":12}""",
            encoded,
        )
    }

    @Test
    fun `encodes an error response carrying a typed code and remediation`() {
        val response = CommandResponse.error(
            id = "r6",
            command = "camera.open",
            error = CommandError(
                code = ErrorCode.PRECONDITION_FAILED,
                message = "Display over other apps is not granted",
                remediation = "Open cam-remote on the device and complete setup",
            ),
            durationMs = 3,
        )

        val encoded = ProtocolJson.encodeResponse(response)
        val roundTripped = ProtocolJson.decodeResponse(encoded)

        assertEquals(CommandStatus.ERROR, roundTripped.status)
        assertEquals(ErrorCode.PRECONDITION_FAILED, roundTripped.error?.code)
        assertEquals("Open cam-remote on the device and complete setup", roundTripped.error?.remediation)
        assertNull(roundTripped.data)
    }
}
