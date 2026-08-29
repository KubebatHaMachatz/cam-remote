package com.camremote.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The single place the wire format is configured.
 *
 * `ignoreUnknownKeys` is deliberate: an agent installed on a handset cannot be upgraded in step with
 * the control application, so a newer client adding a field must not break an older agent.
 * `explicitNulls = false` keeps absent values off the wire entirely rather than sending `null`,
 * which keeps the Python side's "is the key there?" checks honest.
 *
 * kotlinx.serialization is used rather than `org.json` on purpose: `org.json` ships as unimplemented
 * stubs in the Android SDK jar, so any code touching it cannot be unit-tested on a desktop JVM —
 * which is precisely what this project's test strategy depends on.
 */
object ProtocolJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
    }

    /** @throws MalformedRequestException if [text] is not a well-formed request envelope. */
    fun decodeRequest(text: String): CommandRequest = try {
        json.decodeFromString(CommandRequest.serializer(), text)
    } catch (e: SerializationException) {
        throw MalformedRequestException("Malformed command request: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw MalformedRequestException("Malformed command request: ${e.message}", e)
    }

    /** Serialises a request. Used by clients and by the tests that pin the wire format. */
    fun encodeRequest(request: CommandRequest): String =
        json.encodeToString(CommandRequest.serializer(), request)

    /** Serialises a response. This is what the transport actually writes to the socket. */
    fun encodeResponse(response: CommandResponse): String =
        json.encodeToString(CommandResponse.serializer(), response)

    /** @throws MalformedRequestException if [text] is not a well-formed response envelope. */
    fun decodeResponse(text: String): CommandResponse = try {
        json.decodeFromString(CommandResponse.serializer(), text)
    } catch (e: SerializationException) {
        throw MalformedRequestException("Malformed command response: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw MalformedRequestException("Malformed command response: ${e.message}", e)
    }
}
