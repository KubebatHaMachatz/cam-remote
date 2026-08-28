package com.camremote.core.command

import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.CommandError
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.Params
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

/**
 * A single remotely invokable operation.
 *
 * This interface is the extension point of the whole project: adding a capability means writing one
 * implementation and registering it. Commands know nothing about HTTP, about how they were invoked,
 * or about how their result is serialised — which is why the same command object serves the HTTP
 * transport, the instrumented tests, and any transport added later.
 */
interface Command {

    /** Name, description and parameters, surfaced to clients through `system.commands`. */
    val descriptor: CommandDescriptor

    /**
     * A device resource this command needs exclusive use of, or null if it can run concurrently.
     *
     * The camera is physically exclusive; a property read is not. Declaring the need here rather
     * than locking inside the command keeps the policy in one place — the dispatcher.
     */
    val exclusiveResource: DeviceResource? get() = null

    /**
     * How long this command may take, including time spent queued behind [exclusiveResource].
     *
     * A capture legitimately takes seconds; a property read that takes seconds is broken. Per-command
     * budgets let a slow command stay slow without making every other command's failure slow too.
     */
    val timeout: Duration get() = DEFAULT_TIMEOUT

    suspend fun execute(params: Params): CommandOutcome

    companion object {
        val DEFAULT_TIMEOUT = 30.seconds
    }
}

/**
 * A device resource that only one command may hold at a time.
 *
 * Add a value here when a new capability is similarly exclusive (a microphone, say); the dispatcher
 * picks the lock up automatically.
 */
enum class DeviceResource {
    CAMERA,
}

/**
 * What a command produced.
 *
 * Commands return their own failures rather than throwing them, so that an expected outcome ("this
 * device has no rear camera") is not encoded as an exception and cannot be confused with a bug.
 */
sealed interface CommandOutcome {

    data class Success(val data: JsonObject?) : CommandOutcome

    data class Failure(val error: CommandError) : CommandOutcome

    companion object {
        fun failure(code: ErrorCode, message: String, remediation: String? = null): Failure =
            Failure(CommandError(code, message, remediation))
    }
}
