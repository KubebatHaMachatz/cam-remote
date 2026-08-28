package com.camremote.app.transport.http

import com.camremote.core.command.CommandDispatcher
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.CommandRegistry
import com.camremote.core.protocol.CommandStatus
import com.camremote.core.port.OpenPhoto
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.ProtocolJson
import com.camremote.core.security.AccessControl
import com.camremote.core.security.PairingWindow
import com.camremote.core.testing.FakeClock
import com.camremote.core.testing.TestCommand
import io.ktor.client.request.get
import io.ktor.client.request.header
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
 * The HTTP adapter's job is narrow: authenticate, parse, hand the request to the dispatcher, and
 * serialise what comes back. These tests hold it to exactly that, and pin the status codes the
 * Python client branches on.
 */
class CommandApiTest {

    private val clock = FakeClock(1_700_000_000_000)
    private val token = "test-token"
    private val pairing = PairingWindow(clock = clock) { token }

    private val device = DeviceDescription(
        name = "Test Handset",
        model = "Pixel Test",
        androidRelease = "16",
        apiLevel = 37,
    )

    private fun api(): io.ktor.server.application.Application.() -> Unit = {
        commandApi(
            dispatcher = CommandDispatcher(
                CommandRegistry(
                    listOf(
                        TestCommand(name = "system.ping") {
                            CommandOutcome.Success(buildJsonObject { put("pong", JsonPrimitive(true)) })
                        },
                        TestCommand(name = "device.explode") { error("boom") },
                    ),
                ),
                clock,
            ),
            accessControl = AccessControl { token },
            pairingWindow = pairing,
            photos = noPhotos,
            device = device,
        )
    }

    /** The media route has its own test; here it only has to exist. */
    private val noPhotos = object : PhotoStore {
        override fun destinationFor(directory: String?, filename: String) = "/unused"
        override fun record(path: String, capturedAtMillis: Long): StoredPhoto = error("not used here")
        override fun publish(photo: StoredPhoto): String? = null
        override fun open(id: String): OpenPhoto? = null
    }

    @Test
    fun `runs an authenticated command and returns the envelope`() = testApplication {
        application(api())

        val response = client.post("/v1/command") {
            header("Authorization", "Bearer $token")
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
        application(api())

        val response = client.post("/v1/command") {
            header("Authorization", "Bearer $token")
            setBody("""{"id":"req-2","command":"device.explode"}""")
        }

        // A failing command is a successful HTTP exchange: the transport worked, the command did not.
        // Conflating the two would make every client guess whether to retry the request or the command.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ErrorCode.INTERNAL, ProtocolJson.decodeResponse(response.bodyAsText()).error?.code)
    }

    @Test
    fun `rejects a request with no token`() = testApplication {
        application(api())

        val response = client.post("/v1/command") {
            setBody("""{"id":"req-3","command":"system.ping"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(ErrorCode.UNAUTHORIZED, errorCodeOf(response.bodyAsText()))
    }

    @Test
    fun `rejects a request with the wrong token`() = testApplication {
        application(api())

        val response = client.post("/v1/command") {
            header("Authorization", "Bearer not-the-token")
            setBody("""{"id":"req-4","command":"system.ping"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `does not run the command when authentication fails`() = testApplication {
        var ran = false
        application {
            commandApi(
                dispatcher = CommandDispatcher(
                    CommandRegistry(
                        listOf(TestCommand(name = "system.ping") { ran = true; CommandOutcome.Success(null) }),
                    ),
                    clock,
                ),
                accessControl = AccessControl { token },
                pairingWindow = pairing,
                photos = noPhotos,
                device = device,
            )
        }

        client.post("/v1/command") { setBody("""{"id":"r","command":"system.ping"}""") }

        assertEquals(false, ran)
    }

    @Test
    fun `rejects a malformed body with 400 rather than a command error`() = testApplication {
        application(api())

        val response = client.post("/v1/command") {
            header("Authorization", "Bearer $token")
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCode.INVALID_PARAMS, errorCodeOf(response.bodyAsText()))
    }

    @Test
    fun `serves health without a token so a client can confirm reachability before pairing`() =
        testApplication {
            application(api())

            val response = client.get("/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = ProtocolJson.json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("cam-remote", body["service"]?.jsonPrimitive?.content)
            assertEquals("Pixel Test", body["device"]?.jsonObject?.get("model")?.jsonPrimitive?.content)
        }

    @Test
    fun `hands over the token while the pairing window is open`() = testApplication {
        application(api())
        pairing.open()

        val response = client.post("/v1/pair")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = ProtocolJson.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(token, body["token"]?.jsonPrimitive?.content)
    }

    @Test
    fun `refuses to pair when the window is closed`() = testApplication {
        application(api())

        val response = client.post("/v1/pair")

        // 403 rather than 404: the endpoint exists, and telling the caller so lets the CLI print
        // "tap Pair on the device" instead of "is this even a cam-remote agent?".
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCode.UNAUTHORIZED, errorCodeOf(response.bodyAsText()))
    }

    @Test
    fun `pairs only once per window`() = testApplication {
        application(api())
        pairing.open()

        assertEquals(HttpStatusCode.OK, client.post("/v1/pair").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/pair").status)
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
        application(api())

        val response = client.post("/v1/command") {
            header("Authorization", "Bearer $token")
            setBody("""{"id":"req-5","command":"system.ping"}""")
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
