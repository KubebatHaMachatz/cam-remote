package com.camremote.app.transport.http

import com.camremote.core.command.CommandDispatcher
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.CommandRegistry
import com.camremote.core.port.OpenPhoto
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.CommandStatus
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.ProtocolJson
import com.camremote.core.testing.FakeClock
import com.camremote.core.testing.TestCommand
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The HTTP adapter's job is narrow: parse the request, hand it to the dispatcher, and serialise
 * what comes back. These tests hold it to exactly that, and pin the status codes the Python client
 * branches on.
 *
 * There is deliberately no authentication here. The project assumes exactly one agent and one
 * client on the LAN, so the API is open to anyone who can reach the port -- see
 * `docs/DESIGN.md` for the trade-off this makes and why.
 */
class CommandApiTest {

    private val clock = FakeClock(1_700_000_000_000)

    private val device = DeviceDescription(
        name = "Test Handset",
        model = "Pixel Test",
        androidRelease = "16",
        apiLevel = 37,
    )

    /** The media route has its own test; here it only has to exist. */
    private val noPhotos = object : PhotoStore {
        override fun scratchPathFor(filename: String) = "/unused"
        override fun publish(
            scratchPath: String,
            relativeDirectory: String,
            filename: String,
            capturedAtMillis: Long,
        ): StoredPhoto = error("not used here")

        override fun discard(scratchPath: String) = Unit
        override fun open(id: String): OpenPhoto? = null
    }

    private fun api(vararg commands: TestCommand): io.ktor.server.application.Application.() -> Unit = {
        commandApi(
            dispatcher = CommandDispatcher(CommandRegistry(commands.toList()), clock),
            photos = noPhotos,
            device = device,
        )
    }

    @Test
    fun `runs a command and returns the envelope`() = testApplication {
        application(
            api(
                TestCommand(name = "system.ping") {
                    CommandOutcome.Success(buildJsonObject { put("pong", JsonPrimitive(true)) })
                },
            ),
        )

        val response = client.post("/v1/command") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"req-1","command":"system.ping"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = ProtocolJson.decodeResponse(response.bodyAsText())
        assertEquals("req-1", body.id)
        assertEquals(CommandStatus.OK, body.status)
        assertEquals(JsonPrimitive(true), body.data?.get("pong"))
    }

    @Test
    fun `returns 200 with an error envelope when the command itself fails`() = testApplication {
        application(api(TestCommand(name = "device.explode") { error("boom") }))

        val response = client.post("/v1/command") {
            setBody("""{"id":"req-2","command":"device.explode"}""")
        }

        // A failing command is a successful HTTP exchange: the transport worked, the command did not.
        // Conflating the two would make every client guess whether to retry the request or the command.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ErrorCode.INTERNAL, ProtocolJson.decodeResponse(response.bodyAsText()).error?.code)
    }

    @Test
    fun `runs a command with no credential of any kind`() = testApplication {
        application(api(TestCommand(name = "system.ping") { CommandOutcome.Success(null) }))

        val response = client.post("/v1/command") {
            setBody("""{"id":"req-3","command":"system.ping"}""")
        }

        // No pairing code, no token -- the project assumes one agent and one client on the LAN.
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `rejects a malformed body with 400 rather than a command error`() = testApplication {
        application(api())

        val response = client.post("/v1/command") {
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCode.INVALID_PARAMS, errorCodeOf(response.bodyAsText()))
    }

    @Test
    fun `serves health so a client can confirm reachability before anything else`() = testApplication {
        application(api())

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = ProtocolJson.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("cam-remote", body["service"]?.jsonPrimitive?.content)
        assertEquals("Pixel Test", body["device"]?.jsonObject?.get("model")?.jsonPrimitive?.content)
    }

    @Test
    fun `answers an unknown route with a parseable error envelope`() = testApplication {
        application(api())

        val response = client.get("/v1/nonsense")

        assertEquals(HttpStatusCode.NotFound, response.status)
        // Every failure the client can meet carries the same envelope, so it needs one parser.
        assertTrue(errorCodeOf(response.bodyAsText()) != null)
    }

    @Test
    fun `always replies as json`() = testApplication {
        application(api(TestCommand(name = "system.ping") { CommandOutcome.Success(null) }))

        val response = client.post("/v1/command") {
            setBody("""{"id":"req-4","command":"system.ping"}""")
        }

        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    private fun errorCodeOf(body: String): ErrorCode? =
        ProtocolJson.json.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?.let { ErrorCode.valueOf(it) }
}

private fun io.ktor.client.statement.HttpResponse.contentType(): ContentType? =
    io.ktor.http.ContentType.parse(headers[io.ktor.http.HttpHeaders.ContentType] ?: return null)
