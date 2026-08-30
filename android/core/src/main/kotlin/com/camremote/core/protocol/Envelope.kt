package com.camremote.core.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single instruction from the control application.
 *
 * @property id Client-generated correlation id, echoed back untouched. Transports that are not
 *   strictly request/response (a message bus, for example) need it to pair replies with requests.
 * @property command Registry key, e.g. `camera.capture`.
 * @property params Command-specific arguments; see [Params] for typed access.
 */
@Serializable
data class CommandRequest(
    val id: String,
    val command: String,
    val params: Params = Params.EMPTY,
)

/** Whether a command succeeded. Transport-level failures never reach this type. */
enum class CommandStatus { OK, ERROR }

/**
 * A failure, described well enough for a human to act on.
 *
 * @property remediation The concrete next step, when one exists — for instance which setup step to
 *   complete on the handset. Populating this is what stops "PRECONDITION_FAILED" from being a
 *   dead end for whoever is holding the phone.
 */
@Serializable
data class CommandError(
    val code: ErrorCode,
    val message: String,
    val remediation: String? = null,
)

/**
 * The reply to a [CommandRequest].
 *
 * Field order here is the wire order; it is asserted in tests because the Python client and any
 * future client parse it independently.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandResponse(
    val id: String,
    val command: String,
    val status: CommandStatus,
    val data: JsonObject? = null,
    val error: CommandError? = null,
    // Always on the wire, even when zero: a client should never have to distinguish "took no
    // measurable time" from "this agent does not report timings".
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val durationMs: Long = 0,
) {
    companion object {
        /** A successful reply, carrying the command's payload. */
        fun ok(
            id: String,
            command: String,
            data: JsonObject? = null,
            durationMs: Long = 0,
        ): CommandResponse = CommandResponse(
            id = id,
            command = command,
            status = CommandStatus.OK,
            data = data,
            durationMs = durationMs,
        )

        /** A failed reply, built from an error the command produced. */
        fun error(
            id: String,
            command: String,
            error: CommandError,
            durationMs: Long = 0,
        ): CommandResponse = CommandResponse(
            id = id,
            command = command,
            status = CommandStatus.ERROR,
            error = error,
            durationMs = durationMs,
        )

        /** A failed reply, built from its parts. The common case at call sites. */
        fun error(
            id: String,
            command: String,
            code: ErrorCode,
            message: String,
            remediation: String? = null,
            durationMs: Long = 0,
        ): CommandResponse = error(
            id = id,
            command = command,
            error = CommandError(code, message, remediation),
            durationMs = durationMs,
        )
    }
}

/**
 * Self-description of a command, returned by `system.commands`.
 *
 * This is what lets the control application list capabilities it was never compiled against: add a
 * command to the agent and existing clients can discover it without an update.
 */
@Serializable
data class CommandDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ParameterDescriptor> = emptyList(),
    val category: CommandCategory = CommandCategory.DIAGNOSTIC,
)

/**
 * Whether a command is something the agent exists to do, or a tool for working out why it will not.
 *
 * A client groups its listing by this, so an operator meeting the agent for the first time sees the
 * three things it is for before the four ways to interrogate it.
 *
 * [DIAGNOSTIC] is the default deliberately: a command has to declare itself [PRIMARY], because that
 * claim is about the agent's purpose rather than about the command, and it should be made on
 * purpose rather than inherited by forgetting. A new command that says nothing lands among the
 * tools, which is the harmless side of the mistake.
 */
enum class CommandCategory {
    /** One of the capabilities the agent exists to provide. */
    PRIMARY,

    /** A way to inspect the agent or the device, rather than to use it. */
    DIAGNOSTIC,
}

@Serializable
data class ParameterDescriptor(
    val name: String,
    val type: ParameterType,
    val required: Boolean,
    val description: String,
    val default: String? = null,
)

enum class ParameterType { STRING, INT, BOOLEAN, STRING_LIST }
