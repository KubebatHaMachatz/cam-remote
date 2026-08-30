package com.camremote.app.transport.http

import com.camremote.core.command.CommandDispatcher
import com.camremote.core.port.PhotoStore
import com.camremote.core.protocol.CommandError
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.ErrorEnvelope
import com.camremote.core.protocol.HealthResponse
import com.camremote.core.protocol.MalformedRequestException
import com.camremote.core.protocol.ProtocolJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * The HTTP adapter for the driving port.
 *
 * Everything here is Ktor and nothing here is Android, which is what lets these routes be exercised
 * with `testApplication` on a desktop JVM. The adapter deliberately holds no policy: it decides
 * which HTTP status expresses a failure and nothing else. Whether a command may run, how long it may
 * take, and what it returns are all the dispatcher's business.
 *
 * There is no authentication layer here. The project assumes exactly one agent and one client share
 * the LAN, so the API is reachable by anyone who can reach the port -- no token, no pairing code.
 * `docs/DESIGN.md` records that trade-off explicitly, including what it would take to add one back.
 *
 * Responses are serialised with [ProtocolJson] rather than Ktor's ContentNegotiation so that there
 * is exactly one JSON configuration in the project, and the bytes on the wire are the ones the
 * protocol tests assert.
 */
fun Application.commandApi(
    dispatcher: CommandDispatcher,
    photos: PhotoStore,
    device: DeviceDescription,
) {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondError(
                status = HttpStatusCode.NotFound,
                code = ErrorCode.UNKNOWN_COMMAND,
                message = "No such endpoint: ${call.request.local.uri}",
                remediation = "Commands are sent as POST /v1/command; see system.commands for the catalog",
            )
        }
        exception<Throwable> { call, cause ->
            // The dispatcher already converts command failures into responses, so anything landing
            // here is a defect in the adapter itself. Report it in the usual envelope rather than
            // letting Ktor return an HTML error page a JSON client cannot read.
            call.respondError(
                status = HttpStatusCode.InternalServerError,
                code = ErrorCode.INTERNAL,
                message = "${cause::class.simpleName}: ${cause.message}",
                remediation = "This is a defect in the agent; the device log has the stack trace",
            )
        }
    }

    routing {
        get("/v1/health") {
            call.respondJson(
                HttpStatusCode.OK,
                ProtocolJson.json.encodeToString(HealthResponse.serializer(), HealthResponse(device = device)),
            )
        }

        get("/v1/media/{id}") {
            val opened = photos.open(call.parameters["id"].orEmpty())
            if (opened == null) {
                call.respondError(
                    status = HttpStatusCode.NotFound,
                    code = ErrorCode.DEVICE_ERROR,
                    message = "No photo with that id is available on this device",
                    remediation = "Take a photo with camera.capture and use the id it returns",
                )
                return@get
            }

            val filename = opened.photo.path.substringAfterLast('/')
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, filename)
                    .toString(),
            )
            // Streamed rather than read into memory: a full-resolution JPEG is several megabytes
            // and the agent shares a heap with whatever else the phone is doing.
            call.respondOutputStream(
                contentType = ContentType.parse(opened.contentType),
                status = HttpStatusCode.OK,
                contentLength = opened.photo.sizeBytes,
            ) {
                opened.stream.use { it.copyTo(this) }
            }
        }

        post("/v1/command") {
            val request = try {
                ProtocolJson.decodeRequest(call.receiveText())
            } catch (e: MalformedRequestException) {
                call.respondError(
                    status = HttpStatusCode.BadRequest,
                    code = ErrorCode.INVALID_PARAMS,
                    message = e.message ?: "Malformed request",
                    remediation = "Send {\"id\":\"...\",\"command\":\"...\",\"params\":{...}}",
                )
                return@post
            }

            val response = dispatcher.dispatch(request)
            call.respondJson(HttpStatusCode.OK, ProtocolJson.encodeResponse(response))
        }
    }
}

/** Writes an already-serialised JSON body with the right content type. */
private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(text = body, contentType = ContentType.Application.Json, status = status)
}

/**
 * Writes a failure in the standard envelope.
 *
 * Every error the client can meet has the same shape whatever its HTTP status, so the client
 * needs one parser for all of them.
 */
private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: ErrorCode,
    message: String,
    remediation: String? = null,
) {
    respondJson(
        status,
        ProtocolJson.json.encodeToString(
            ErrorEnvelope.serializer(),
            ErrorEnvelope(CommandError(code = code, message = message, remediation = remediation)),
        ),
    )
}
