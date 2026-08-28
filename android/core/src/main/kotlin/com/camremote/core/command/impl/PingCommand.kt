package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.port.Clock
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.Params
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Liveness check.
 *
 * Deliberately the first command written: it exercises the whole path from transport through
 * dispatcher to response while touching no device capability at all, so when a real feature later
 * misbehaves it is obvious whether the plumbing or the feature is at fault. It also returns the
 * device clock, which is the cheapest way to spot a handset whose time is wrong.
 */
class PingCommand(private val clock: Clock) : Command {

    override val descriptor = CommandDescriptor(
        name = "system.ping",
        description = "Check that the agent is reachable and report the device clock.",
    )

    override suspend fun execute(params: Params): CommandOutcome = CommandOutcome.Success(
        buildJsonObject {
            put("pong", JsonPrimitive(true))
            put("deviceTimeMillis", JsonPrimitive(clock.nowMillis()))
        },
    )
}
