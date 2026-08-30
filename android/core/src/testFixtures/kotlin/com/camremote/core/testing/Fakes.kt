package com.camremote.core.testing

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.command.DeviceResource
import com.camremote.core.port.Clock
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.Params
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A clock that only moves when a test tells it to, so asserted durations are exact. */
class FakeClock(private var now: Long = 0) : Clock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) { now += millis }
}

/**
 * A command whose behaviour a test supplies inline.
 *
 * Every port in this codebase has a fake, but commands need one too: the dispatcher's contract is
 * about what it does *around* a command, so the command itself should be uninteresting.
 */
class TestCommand(
    name: String = "test.command",
    override val exclusiveResource: DeviceResource? = null,
    override val timeout: Duration = 30.seconds,
    private val body: suspend (Params) -> CommandOutcome,
) : Command {
    override val descriptor = CommandDescriptor(name = name, description = "test double")
    override suspend fun execute(params: Params): CommandOutcome = body(params)
}
