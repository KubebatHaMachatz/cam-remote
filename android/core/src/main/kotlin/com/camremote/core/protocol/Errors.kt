package com.camremote.core.protocol

/**
 * Machine-readable failure classification.
 *
 * The control application branches on these, so they are part of the wire contract: rename one and
 * every client breaks. New codes may be appended; existing ones are never repurposed.
 */
enum class ErrorCode {
    /** No command is registered under the requested name. */
    UNKNOWN_COMMAND,

    /** The command exists but the supplied parameters are missing, ill-typed, or rejected. */
    INVALID_PARAMS,

    /** An Android runtime permission the command needs has not been granted. */
    PERMISSION_DENIED,

    /** Device-side setup is incomplete — e.g. the overlay permission needed to launch an activity. */
    PRECONDITION_FAILED,

    /** The device refused or failed the operation (no rear camera, camera in use, I/O failure). */
    DEVICE_ERROR,

    /** The request carried a missing or incorrect bearer token. */
    UNAUTHORIZED,

    /** The command exceeded its time budget and was cancelled. */
    TIMEOUT,

    /** An unexpected failure. Anything reaching the client as INTERNAL is a bug worth logging. */
    INTERNAL,
}

/** Base class for failures that occur before a command ever runs. */
sealed class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The payload was not a well-formed request envelope. Surfaced as HTTP 400, not a command error. */
class MalformedRequestException(message: String, cause: Throwable? = null) :
    ProtocolException(message, cause)

/**
 * A parameter was absent, of the wrong type, or failed validation.
 *
 * Thrown from [Params] accessors so that commands can read their inputs as straight-line code and
 * let the dispatcher translate the failure into [ErrorCode.INVALID_PARAMS].
 */
class InvalidParamsException(message: String) : ProtocolException(message)
