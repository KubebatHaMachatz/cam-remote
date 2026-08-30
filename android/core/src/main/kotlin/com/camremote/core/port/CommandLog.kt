package com.camremote.core.port

import com.camremote.core.protocol.CommandRequest
import com.camremote.core.protocol.CommandResponse

/**
 * Narrates what the agent did, wherever this device keeps its logs.
 *
 * A headless agent on a phone in another room is otherwise opaque: the operator sees what came back
 * over the network and nothing about what happened on the handset. This is the other half of that
 * picture, and the half that survives — a response is read once and gone, while the device log is
 * still there tomorrow when someone asks why a photograph is missing.
 *
 * It hangs off the dispatcher rather than off a transport, so every command is recorded no matter
 * how it arrived; a second transport would inherit this without knowing it exists.
 */
interface CommandLog {

    /** A command has arrived and is about to run. */
    fun received(request: CommandRequest)

    /**
     * A command has finished, successfully or not.
     *
     * Not called when the caller disconnects mid-command: cancellation is not an outcome, and
     * claiming one in the log would be a lie.
     */
    fun completed(request: CommandRequest, response: CommandResponse)

    companion object {
        /** Discards everything. The default, and what a unit test uses unless it asserts on logging. */
        val None: CommandLog = object : CommandLog {
            override fun received(request: CommandRequest) = Unit
            override fun completed(request: CommandRequest, response: CommandResponse) = Unit
        }
    }
}
