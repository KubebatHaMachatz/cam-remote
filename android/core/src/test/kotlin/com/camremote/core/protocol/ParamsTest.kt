package com.camremote.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The accessors are the only place a request's types are enforced, so a value of the wrong type has
 * to be refused here or it reaches a command disguised as something it is not.
 *
 * `JsonPrimitive.content` returns the source text for *any* scalar, which made every accessor
 * quietly lenient: `{"filename": true}` became the file `true.jpg`, and `{"filename": null}` became
 * `null.jpg`, because `JsonNull` is itself a `JsonPrimitive`. Both are specified against here.
 */
class ParamsTest {

    private fun params(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        Params(buildJsonObject(build))

    // ---- optString -------------------------------------------------------------------------

    @Test
    fun `reads a string`() {
        assertEquals("door.jpg", params { put("filename", "door.jpg") }.optString("filename"))
    }

    @Test
    fun `treats an absent key as absent`() {
        assertNull(params { put("other", "x") }.optString("filename"))
    }

    @Test
    fun `treats an explicit null as absent rather than as the text null`() {
        // JsonNull is a JsonPrimitive whose content is the four characters "null", so reading it
        // as a string would name a file null.jpg.
        assertNull(params { put("filename", JsonNull) }.optString("filename"))
    }

    @Test
    fun `refuses a number where a string is required`() {
        assertFailsWith<InvalidParamsException> {
            params { put("filename", 123) }.optString("filename")
        }
    }

    @Test
    fun `refuses a boolean where a string is required`() {
        assertFailsWith<InvalidParamsException> {
            params { put("filename", true) }.optString("filename")
        }
    }

    @Test
    fun `refuses an object or an array where a scalar is required`() {
        assertFailsWith<InvalidParamsException> {
            params { putJsonObject("filename") { put("a", 1) } }.optString("filename")
        }
        assertFailsWith<InvalidParamsException> {
            params { putJsonArray("filename") { add(JsonPrimitive("a")) } }.optString("filename")
        }
    }

    @Test
    fun `requireString refuses a number as firmly as it refuses an absence`() {
        assertFailsWith<InvalidParamsException> { params { put("key", 7) }.requireString("key") }
        assertFailsWith<InvalidParamsException> { params { }.requireString("key") }
        assertFailsWith<InvalidParamsException> { params { put("key", "  ") }.requireString("key") }
    }

    // ---- optStringList ---------------------------------------------------------------------

    @Test
    fun `reads a list of strings`() {
        val read = params {
            putJsonArray("keys") { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }
        }.optStringList("keys")

        assertEquals(listOf("a", "b"), read)
    }

    @Test
    fun `refuses a list containing a number`() {
        assertFailsWith<InvalidParamsException> {
            params { putJsonArray("keys") { add(JsonPrimitive("a")); add(JsonPrimitive(2)) } }
                .optStringList("keys")
        }
    }

    @Test
    fun `refuses a bare value where a list is required`() {
        assertFailsWith<InvalidParamsException> {
            params { put("keys", "a") }.optStringList("keys")
        }
    }

    // ---- optInt / optBoolean ---------------------------------------------------------------

    @Test
    fun `reads a number`() {
        assertEquals(60, params { put("jpegQuality", 60) }.optInt("jpegQuality", 95))
    }

    @Test
    fun `falls back to the default when a number is absent`() {
        assertEquals(95, params { }.optInt("jpegQuality", 95))
    }

    @Test
    fun `refuses a quoted number, so the contract is strict in both directions`() {
        // The mirror of refusing a number for a string: if one direction is enforced and the
        // other is not, "typed protocol" means nothing in particular.
        assertFailsWith<InvalidParamsException> {
            params { put("jpegQuality", "60") }.optInt("jpegQuality", 95)
        }
    }

    @Test
    fun `refuses a non-integer where an integer is required`() {
        assertFailsWith<InvalidParamsException> {
            params { put("jpegQuality", true) }.optInt("jpegQuality", 95)
        }
        assertFailsWith<InvalidParamsException> {
            params { put("jpegQuality", 1.5) }.optInt("jpegQuality", 95)
        }
    }

    @Test
    fun `reads a boolean`() {
        assertEquals(true, params { put("flag", true) }.optBoolean("flag", false))
        assertEquals(false, params { }.optBoolean("flag", false))
    }

    @Test
    fun `refuses a quoted boolean`() {
        assertFailsWith<InvalidParamsException> {
            params { put("flag", "true") }.optBoolean("flag", false)
        }
    }
}
