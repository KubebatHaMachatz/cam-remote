package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.logic.PropertyKeys
import com.camremote.core.port.PropertyReader
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.ParameterDescriptor
import com.camremote.core.protocol.ParameterType
import com.camremote.core.protocol.Params
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Reads Android system properties — the assignment's `getprop` requirement.
 *
 * Accepts either a single `key` or a list of `keys`, because the expensive part of a remote control
 * link is the round trip, not the read.
 */
class GetPropCommand(private val properties: PropertyReader) : Command {

    override val descriptor = CommandDescriptor(
        name = "device.getprop",
        description = "Read one or more Android system properties.",
        parameters = listOf(
            ParameterDescriptor(
                name = "key",
                type = ParameterType.STRING,
                required = false,
                description = "A single property name, e.g. ro.product.model.",
            ),
            ParameterDescriptor(
                name = "keys",
                type = ParameterType.STRING_LIST,
                required = false,
                description = "Several property names to read in one request. Takes precedence over 'key'.",
            ),
        ),
    )

    // Reading a property blocks no hardware, so concurrent reads are free.
    override val exclusiveResource = null

    override val timeout = 10.seconds

    override suspend fun execute(params: Params): CommandOutcome {
        val keys = requestedKeys(params).map(PropertyKeys::validate)

        return try {
            CommandOutcome.Success(
                buildJsonObject {
                    put(
                        "properties",
                        buildJsonObject {
                            keys.forEach { key ->
                                put(key, properties.read(key)?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        },
                    )
                },
            )
        } catch (e: Exception) {
            // The property store being unreadable is a device condition, not a defect in the agent,
            // so it gets its own code rather than surfacing as INTERNAL.
            CommandOutcome.failure(
                code = ErrorCode.DEVICE_ERROR,
                message = "Could not read device properties: ${e.message}",
                remediation = "Confirm the device is responsive; this is unusual on a healthy handset",
            )
        }
    }

    private fun requestedKeys(params: Params): List<String> {
        val many = params.optStringList("keys")
        if (many != null) {
            if (many.isEmpty()) {
                throw InvalidParamsException("Parameter 'keys' must contain at least one property name")
            }
            if (many.size > MAX_KEYS_PER_REQUEST) {
                throw InvalidParamsException(
                    "Parameter 'keys' may hold at most $MAX_KEYS_PER_REQUEST names, got ${many.size}",
                )
            }
            return many
        }
        return listOf(params.requireString("key"))
    }

    private companion object {
        const val MAX_KEYS_PER_REQUEST = 64
    }
}
