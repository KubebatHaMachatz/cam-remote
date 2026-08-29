package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.ProtocolJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject

/**
 * Returns the agent's own catalog.
 *
 * This is what makes the command set discoverable: a control application can list capabilities it
 * was never compiled against, so adding a command to the agent does not oblige anyone to update
 * their client to find out about it.
 *
 * The descriptors arrive through a supplier rather than a [com.camremote.core.command.CommandRegistry]
 * reference because this command is itself in the registry, and a constructor cannot take something
 * that does not exist yet.
 */
class ListCommandsCommand(
    private val descriptors: () -> List<CommandDescriptor>,
) : Command {

    override val descriptor = CommandDescriptor(
        name = "system.commands",
        description = "List every command this agent supports, with its parameters.",
    )

    /** Returns the live catalog, so clients can discover commands they predate. */
    override suspend fun execute(params: Params): CommandOutcome = CommandOutcome.Success(
        buildJsonObject {
            put(
                "commands",
                ProtocolJson.json.encodeToJsonElement(
                    ListSerializer(CommandDescriptor.serializer()),
                    descriptors(),
                ),
            )
        },
    )
}
