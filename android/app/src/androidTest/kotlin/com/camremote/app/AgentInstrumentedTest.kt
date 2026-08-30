package com.camremote.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.camremote.app.di.AppContainer
import com.camremote.app.transport.http.HttpCommandServer
import com.camremote.app.transport.http.commandApi
import com.camremote.core.protocol.CommandRequest
import com.camremote.core.protocol.CommandStatus
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.Params
import com.camremote.core.protocol.ProtocolJson
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tests that need a real handset.
 *
 * Everything decidable on a desktop JVM is already covered by the unit tests, so this file
 * deliberately covers only what they structurally cannot: that a real sensor produces a real JPEG,
 * that `getprop` reads the real property store, and that the HTTP server answers on a real socket.
 */
@RunWith(AndroidJUnit4::class)
class AgentInstrumentedTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var lifecycle: TestLifecycleOwner
    private val published = mutableListOf<Uri>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer.from(context)
        lifecycle = TestLifecycleOwner().also { it.moveToStarted() }
        grantCameraIfTheDeviceAllowsIt()
    }

    /**
     * Tries to grant the camera permission, and shrugs if the device refuses.
     *
     * `GrantPermissionRule` is not used here because it is not portable. Several OEM builds -- the
     * ColorOS handset this was developed against among them -- refuse runtime permission grants from
     * the shell and from UiAutomation alike, and the rule turns that refusal into an error that
     * fails every test in the class, including the ones that never touch the camera. Attempting the
     * grant and then checking what actually happened keeps the rest of the suite meaningful, and the
     * camera test says plainly why it skipped.
     */
    private fun grantCameraIfTheDeviceAllowsIt() {
        if (hasCameraPermission()) return
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.CAMERA,
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @After
    fun tearDown() {
        published.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test
    fun agentAnswersPingThroughTheRealDispatcher() = runBlocking {
        val response = container.dispatcherFor(lifecycle)
            .dispatch(CommandRequest(id = "t1", command = "system.ping"))

        assertEquals(CommandStatus.OK, response.status)
        assertEquals(true, response.data?.get("pong")?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun getPropReadsTheDevicesActualPropertyStore() = runBlocking {
        val response = container.dispatcherFor(lifecycle).dispatch(
            CommandRequest(
                id = "t2",
                command = "device.getprop",
                params = Params.of("key" to "ro.build.version.sdk"),
            ),
        )

        assertEquals(CommandStatus.OK, response.status)
        val reported = response.data?.get("properties")?.jsonObject
            ?.get("ro.build.version.sdk")?.jsonPrimitive?.content

        // Not merely "some string came back": it must be this device's real API level.
        assertEquals(Build.VERSION.SDK_INT.toString(), reported)
    }

    @Test
    fun capturingProducesARealJpegFromTheRearSensor() = runBlocking {
        assumeTrue(
            "CAMERA is not granted and this device does not permit granting it from a test. " +
                "Open cam-remote on the handset once (or run a camera command and grant it " +
                "when prompted), then run this again.",
            hasCameraPermission(),
        )

        val response = container.dispatcherFor(lifecycle).dispatch(
            CommandRequest(
                id = "t3",
                command = "camera.capture",
                params = Params.of("filename" to "instrumented-test"),
            ),
        )

        assertEquals(
            "capture failed: ${response.error?.message}",
            CommandStatus.OK,
            response.status,
        )

        val data = response.data!!
        val uri = Uri.parse(data["uri"]!!.jsonPrimitive.content).also(published::add)

        // Where a person could actually find it, which is the point of using MediaStore at all.
        assertEquals(
            "Documents/cam-remote/instrumented-test.jpg",
            data["path"]!!.jsonPrimitive.content,
        )

        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue("the capture should not be empty", bytes.size > 1_000)

        // The magic bytes, because "a row exists" is not the same claim as "a photograph was taken".
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun capturesAreVisibleInSharedStorageWithNoStoragePermission() = runBlocking {
        assumeTrue("CAMERA is not granted; see the other capture test.", hasCameraPermission())

        // The claim under test is the whole reason captures go through MediaStore: this app holds
        // no storage permission of any kind, yet the photo lands where the user can reach it.
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, "android.permission.WRITE_EXTERNAL_STORAGE"),
        )

        val response = container.dispatcherFor(lifecycle).dispatch(
            CommandRequest(
                id = "t3b",
                command = "camera.capture",
                params = Params.of("path" to "cam-remote/instrumented", "filename" to "shared-check"),
            ),
        )
        assertEquals(CommandStatus.OK, response.status)
        val uri = Uri.parse(response.data!!["uri"]!!.jsonPrimitive.content).also(published::add)

        // Queried back through MediaStore rather than trusting the response we just received.
        val columns = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        context.contentResolver.query(uri, columns, null, null, null)!!.use { cursor ->
            assertTrue("MediaStore should have a row for the capture", cursor.moveToFirst())
            assertEquals("Documents/cam-remote/instrumented/", cursor.getString(0))
            assertEquals("shared-check.jpg", cursor.getString(1))
            assertTrue("the stored photo should not be empty", cursor.getLong(2) > 1_000)
        }
    }

    @Test
    fun aFailedCaptureLeavesNothingInTheUsersDocuments() = runBlocking {
        assumeTrue("CAMERA is not granted; see the other capture test.", hasCameraPermission())

        // A destination that PhotoPaths refuses. The sensor is never touched, so nothing can be
        // left behind -- but the agent must say so rather than reporting a success.
        val response = container.dispatcherFor(lifecycle).dispatch(
            CommandRequest(
                id = "t3c",
                command = "camera.capture",
                params = Params.of("path" to "../escape"),
            ),
        )

        assertEquals(CommandStatus.ERROR, response.status)
        assertEquals(ErrorCode.INVALID_PARAMS, response.error?.code)
    }

    @Test
    fun httpServerAnswersOnARealSocket() {
        val port = freePort()
        val server = HttpCommandServer(port) {
            commandApi(
                dispatcher = container.dispatcherFor(lifecycle),
                photos = container.photos,
                device = container.deviceDescription(),
            )
        }
        server.start()

        try {
            val (status, body) = post(port, """{"id":"t4","command":"system.ping"}""")

            assertEquals(200, status)
            assertEquals(CommandStatus.OK, ProtocolJson.decodeResponse(body).status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknownCommandsAreReportedRatherThanCrashingTheAgent() = runBlocking {
        val response = container.dispatcherFor(lifecycle)
            .dispatch(CommandRequest(id = "t5", command = "camera.teleport"))

        assertEquals(ErrorCode.UNKNOWN_COMMAND, response.error?.code)
    }

    /**
     * Posts a command over a bare socket.
     *
     * `HttpURLConnection` refuses this: since API 28 an app may not make cleartext HTTP requests
     * unless its manifest opts in, and the agent serves plain HTTP on the LAN by design. That policy
     * governs requests the app *makes*, not connections it accepts, so it constrains only this test
     * connecting to itself — relaxing the shipped app's network policy to satisfy a test would be
     * the wrong trade, so the test speaks HTTP itself instead.
     */
    private fun post(port: Int, body: String): Pair<Int, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val payload = body.toByteArray()
            val request = buildString {
                append("POST /v1/command HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray())
                write(payload)
                flush()
            }

            val raw = socket.getInputStream().bufferedReader().readText()
            val status = raw.substringAfter(' ').substringBefore(' ').trim().toInt()
            return status to raw.substringAfter("\r\n\r\n")
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A lifecycle CameraX can bind to, without needing the real service to be running. */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveToStarted() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                registry.currentState = Lifecycle.State.RESUMED
            }
        }
    }
}
