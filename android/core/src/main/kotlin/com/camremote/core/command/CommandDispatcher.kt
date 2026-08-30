package com.camremote.core.command

import com.camremote.core.port.Clock
import com.camremote.core.port.CommandLog
import com.camremote.core.protocol.CommandRequest
import com.camremote.core.protocol.CommandResponse
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.InvalidParamsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The driving port: the one entry point through which every transport reaches the commands.
 *
 * Everything a caller experiences that is not the command's own business lives here — name lookup,
 * exclusivity, time budgets, and turning failures into a typed response. Keeping it in one place is
 * what lets a new transport be written without re-deciding any of it, and lets the instrumented
 * tests exercise real commands with no HTTP involved at all.
 */
class CommandDispatcher(
    private val registry: CommandRegistry,
    private val clock: Clock,
    private val locks: ResourceLocks = ResourceLocks(),
    private val log: CommandLog = CommandLog.None,
) {

    /**
     * Runs one request and always answers with a response.
     *
     * Nothing thrown by a command escapes here except cancellation: an unknown name, a bad
     * parameter, a timeout, or an outright bug all become a typed [CommandResponse], because a
     * transport that has to interpret exceptions is a transport that will get it wrong.
     */
    suspend fun dispatch(request: CommandRequest): CommandResponse {
        log.received(request)
        val response = run(request)
        log.completed(request, response)
        return response
    }

    /**
     * Produces the response, leaving [dispatch] to record it.
     *
     * Split out because there are seven ways to answer and logging each of them at its own return
     * would be seven chances to forget one. Cancellation escapes this function rather than
     * returning, so it is deliberately never logged as an outcome.
     */
    private suspend fun run(request: CommandRequest): CommandResponse {
        val startedAt = clock.nowMillis()

        /** Milliseconds since the request arrived, for the response's timing field. */
        fun elapsed() = clock.nowMillis() - startedAt

        val command = registry[request.command] ?: return CommandResponse.error(
            id = request.id,
            command = request.command,
            code = ErrorCode.UNKNOWN_COMMAND,
            message = "Unknown command '${request.command}'. Known commands: ${registry.names.joinToString()}",
            remediation = "Call system.commands for the catalog this device supports",
            durationMs = elapsed(),
        )

        return try {
            val outcome = withTimeout(command.timeout) {
                locks.withResource(command.exclusiveResource) { command.execute(request.params) }
            }
            when (outcome) {
                is CommandOutcome.Success -> CommandResponse.ok(
                    id = request.id,
                    command = request.command,
                    data = outcome.data,
                    durationMs = elapsed(),
                )

                is CommandOutcome.Failure -> CommandResponse.error(
                    id = request.id,
                    command = request.command,
                    error = outcome.error,
                    durationMs = elapsed(),
                )
            }
        } catch (e: TimeoutCancellationException) {
            // Subclass of CancellationException, so this catch must stay above the one below.
            CommandResponse.error(
                id = request.id,
                command = request.command,
                code = ErrorCode.TIMEOUT,
                message = "Command '${request.command}' exceeded its ${command.timeout} budget",
                remediation = if (command.exclusiveResource != null) {
                    "The ${command.exclusiveResource} may be busy with another request; retry shortly"
                } else {
                    "Retry, and check the device is awake and responsive"
                },
                durationMs = elapsed(),
            )
        } catch (e: InvalidParamsException) {
            CommandResponse.error(
                id = request.id,
                command = request.command,
                code = ErrorCode.INVALID_PARAMS,
                message = e.message ?: "Invalid parameters",
                remediation = "Call system.commands to see the parameters '${request.command}' accepts",
                durationMs = elapsed(),
            )
        } catch (e: CancellationException) {
            // The caller went away, or the service is shutting down. Never swallow this: doing so
            // breaks structured concurrency and leaves the coroutine running after its scope died.
            throw e
        } catch (e: Exception) {
            // A bug in one command must not take the agent down for every other command.
            CommandResponse.error(
                id = request.id,
                command = request.command,
                code = ErrorCode.INTERNAL,
                message = "${e::class.simpleName}: ${e.message}",
                remediation = "This is a defect in the agent; the device log has the stack trace",
                durationMs = elapsed(),
            )
        }
    }
}
