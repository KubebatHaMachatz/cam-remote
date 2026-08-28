package com.camremote.core.command

import com.camremote.core.protocol.CommandDescriptor

/**
 * The set of commands this agent can run, keyed by name.
 *
 * Registration is an explicit list built in one place rather than classpath scanning or annotation
 * processing. That costs one line per new command and buys something worth more on a project this
 * size: the complete set of capabilities is readable in a single file, and code shrinkers cannot
 * silently strip a command that nothing appears to reference.
 */
class CommandRegistry(commands: List<Command>) {

    private val byName: Map<String, Command>

    init {
        val duplicates = commands.groupBy { it.descriptor.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate command registration for: ${duplicates.sorted().joinToString()}"
        }
        byName = commands.associateBy { it.descriptor.name }
    }

    operator fun get(name: String): Command? = byName[name]

    /** Registered names, sorted, for error messages that tell the caller what does exist. */
    val names: List<String> = byName.keys.sorted()

    /** The catalog returned by `system.commands`, sorted so client output is stable. */
    fun descriptors(): List<CommandDescriptor> = byName.values
        .map { it.descriptor }
        .sortedBy { it.name }
}
