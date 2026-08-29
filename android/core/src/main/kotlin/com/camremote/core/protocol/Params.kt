package com.camremote.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Typed, validating access to a command's parameter object.
 *
 * Commands never touch raw JSON. Every accessor either returns a usable value or throws
 * [InvalidParamsException], which the dispatcher maps to a single error code — so parameter
 * validation is written once here instead of being re-implemented, slightly differently, in every
 * command.
 */
@Serializable(with = ParamsSerializer::class)
class Params(val raw: JsonObject) {

    val keys: Set<String> get() = raw.keys

    /** Returns the value of [key] as a string, or null when absent. */
    fun optString(key: String): String? = primitive(key)?.content

    /** Returns the value of [key] as a string, or throws when absent or blank. */
    fun requireString(key: String): String {
        val value = optString(key)
            ?: throw InvalidParamsException("Missing required parameter '$key'")
        if (value.isBlank()) throw InvalidParamsException("Parameter '$key' must not be blank")
        return value
    }

    /** Returns the value of [key] as an int, or [default] when absent. Throws if present but not an int. */
    fun optInt(key: String, default: Int): Int {
        val primitive = primitive(key) ?: return default
        return primitive.intOrNull
            ?: throw InvalidParamsException("Parameter '$key' must be an integer")
    }

    /** Returns the value of [key] as a boolean, or [default] when absent. Throws if present but not a boolean. */
    fun optBoolean(key: String, default: Boolean): Boolean {
        val primitive = primitive(key) ?: return default
        return primitive.booleanOrNull
            ?: throw InvalidParamsException("Parameter '$key' must be a boolean")
    }

    /** Returns the value of [key] as a list of strings, or null when absent. */
    fun optStringList(key: String): List<String>? {
        val element = raw[key] ?: return null
        val array = element as? JsonArray
            ?: throw InvalidParamsException("Parameter '$key' must be an array of strings")
        return array.map {
            (it as? JsonPrimitive)?.content
                ?: throw InvalidParamsException("Parameter '$key' must contain only strings")
        }
    }

    /**
     * The scalar at [key], or null when absent.
     *
     * Rejects objects and arrays here so every accessor above gets the same error for the same
     * mistake, rather than each discovering it differently.
     */
    private fun primitive(key: String): JsonPrimitive? {
        val element = raw[key] ?: return null
        return element as? JsonPrimitive
            ?: throw InvalidParamsException("Parameter '$key' must be a scalar value")
    }

    // Value semantics, so a test can compare two Params directly and so Params behaves like the
    // data classes it sits inside. A plain class is used rather than a data class because the
    // custom serializer below needs to be attached to the type.

    override fun equals(other: Any?): Boolean = other is Params && other.raw == raw

    /** Consistent with [equals]. */
    override fun hashCode(): Int = raw.hashCode()

    /** The underlying JSON, which is what a failed assertion or a log line wants to show. */
    override fun toString(): String = raw.toString()

    companion object {
        val EMPTY = Params(JsonObject(emptyMap()))

        /** Convenience for tests and for commands that build a nested payload. */
        fun of(vararg pairs: Pair<String, String>): Params =
            Params(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }))
    }
}

/** Serializes [Params] transparently as the underlying JSON object. */
object ParamsSerializer : KSerializer<Params> {
    private val delegate = JsonObject.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    /** Writes the wrapped object, so `Params` is invisible on the wire. */
    override fun serialize(encoder: Encoder, value: Params) = delegate.serialize(encoder, value.raw)

    /** Wraps whatever object was read; validation happens later, in the accessors. */
    override fun deserialize(decoder: Decoder): Params = Params(delegate.deserialize(decoder))
}
