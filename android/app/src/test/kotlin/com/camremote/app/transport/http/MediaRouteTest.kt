package com.camremote.app.transport.http

import com.camremote.core.command.CommandDispatcher
import com.camremote.core.command.CommandRegistry
import com.camremote.core.port.OpenPhoto
import com.camremote.core.port.PhotoStore
import com.camremote.core.port.StoredPhoto
import com.camremote.core.protocol.DeviceDescription
import com.camremote.core.testing.FakeClock
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The download route is what makes "save the image to a specified location" useful to an operator
 * who is not holding the phone.
 */
class MediaRouteTest {

    private val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private val photos = object : PhotoStore {
        override fun destinationFor(directory: String?, filename: String) = "/unused"
        override fun record(path: String, capturedAtMillis: Long) = error("not used here")
        override fun publish(photo: StoredPhoto): String? = null
        override fun open(id: String): OpenPhoto? = if (id == "known-id") {
            OpenPhoto(
                photo = StoredPhoto(
                    id = "known-id",
                    path = "/data/pictures/camremote-20231114-221319-123.jpg",
                    sizeBytes = bytes.size.toLong(),
                    capturedAtMillis = 0,
                ),
                contentType = "image/jpeg",
                stream = bytes.inputStream(),
            )
        } else {
            null
        }
    }

    private fun io.ktor.server.application.Application.configure() {
        commandApi(
            dispatcher = CommandDispatcher(CommandRegistry(emptyList()), FakeClock()),
            photos = photos,
            device = DeviceDescription("Test", "Test", "16", 37),
        )
    }

    @Test
    fun `serves a stored photo`() = testApplication {
        application { configure() }

        val response = client.get("/v1/media/known-id")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsBytes().contentEquals(bytes))
    }

    @Test
    fun `announces the type and size so a client can save it directly`() = testApplication {
        application { configure() }

        val response = client.get("/v1/media/known-id")

        assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
        assertEquals(bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        // The original filename travels with the bytes, so a download keeps its timestamped name.
        assertTrue(
            response.headers[HttpHeaders.ContentDisposition]!!
                .contains("camremote-20231114-221319-123.jpg"),
        )
    }

    @Test
    fun `reports an unknown id as not found`() = testApplication {
        application { configure() }

        val response = client.get("/v1/media/no-such-id")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
