package com.camremote.core.logic

import com.camremote.core.port.ExtraValue
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deciding *what* intent to fire is separable from firing it, and only the deciding half has any
 * branching in it. Keeping that half here means the Android adapter is a handful of lines with
 * nothing left to get wrong.
 *
 * The candidate list is what makes the command portable. There is no single intent that every
 * Android device answers: `STILL_IMAGE_CAMERA` is the semantically correct one and is what ColorOS
 * answers, but plenty of builds — bare AOSP system images especially — declare only some of these,
 * or none.
 */
class CameraAppLaunchTest {

    @Test
    fun `tries the still-image camera action first`() {
        val candidates = CameraAppLaunch.candidatesFor(Params.EMPTY)

        assertEquals("android.media.action.STILL_IMAGE_CAMERA", candidates.first().action)
        assertEquals("still_image_camera", candidates.first().strategy)
    }

    @Test
    fun `falls back through the camera app category to image capture`() {
        val candidates = CameraAppLaunch.candidatesFor(Params.EMPTY)

        assertEquals(
            listOf("still_image_camera", "app_camera_category", "image_capture"),
            candidates.map { it.strategy },
        )
    }

    @Test
    fun `the category candidate is a plain launch of the camera app`() {
        val candidate = CameraAppLaunch.candidatesFor(Params.EMPTY)[1]

        assertEquals("android.intent.action.MAIN", candidate.action)
        assertEquals(setOf("android.intent.category.APP_CAMERA"), candidate.categories)
    }

    @Test
    fun `image capture is tried last because it asks for a result nobody will collect`() {
        // ACTION_IMAGE_CAPTURE puts the camera app into "take one and hand it back" mode. Started
        // from a service with no result receiver, some apps sit on a confirm screen with nowhere to
        // return to — usable as a fallback, wrong as a first choice.
        val last = CameraAppLaunch.candidatesFor(Params.EMPTY).last()

        assertEquals("android.media.action.IMAGE_CAPTURE", last.action)
    }

    @Test
    fun `every candidate launches into its own task`() {
        // A service has no task of its own to launch into.
        assertTrue(CameraAppLaunch.candidatesFor(Params.EMPTY).all { it.newTask })
    }

    @Test
    fun `scopes every candidate to a requested package`() {
        val candidates = CameraAppLaunch.candidatesFor(Params.of("package" to "com.sec.android.app.camera"))

        assertTrue(candidates.all { it.targetPackage == "com.sec.android.app.camera" })
    }

    @Test
    fun `adds the plain launcher entry when a package is named`() {
        // If someone names a package, opening its main activity is a reasonable last resort even
        // when it declares none of the camera intents.
        val candidates = CameraAppLaunch.candidatesFor(Params.of("package" to "com.example.camera"))

        assertEquals("launcher_entry", candidates.last().strategy)
        assertEquals(setOf("android.intent.category.LAUNCHER"), candidates.last().categories)
    }

    @Test
    fun `offers no launcher entry when no package was named`() {
        // Unscoped, it would resolve to whatever the device considers its main launcher activity,
        // which is emphatically not a camera.
        assertTrue(CameraAppLaunch.candidatesFor(Params.EMPTY).none { it.strategy == "launcher_entry" })
    }

    @Test
    fun `hints the front lens on every candidate when requested`() {
        val candidates = CameraAppLaunch.candidatesFor(Params.of("lens" to "front"))

        // Both spellings are sent because which one an OEM camera app honours is anyone's guess;
        // this is a hint, and the command's contract says so.
        assertTrue(
            candidates.all {
                it.extras["android.intent.extras.CAMERA_FACING"] == ExtraValue.IntValue(1) &&
                    it.extras["android.intent.extras.LENS_FACING_FRONT"] == ExtraValue.BoolValue(true)
            },
        )
    }

    @Test
    fun `hints the rear lens when requested`() {
        val candidate = CameraAppLaunch.candidatesFor(Params.of("lens" to "rear")).first()

        assertEquals(ExtraValue.IntValue(0), candidate.extras["android.intent.extras.CAMERA_FACING"])
        assertEquals(
            ExtraValue.BoolValue(false),
            candidate.extras["android.intent.extras.LENS_FACING_FRONT"],
        )
    }

    @Test
    fun `accepts back as a synonym for rear`() {
        assertEquals(
            CameraAppLaunch.candidatesFor(Params.of("lens" to "rear")),
            CameraAppLaunch.candidatesFor(Params.of("lens" to "back")),
        )
    }

    @Test
    fun `is case insensitive about the lens`() {
        assertEquals(
            CameraAppLaunch.candidatesFor(Params.of("lens" to "front")),
            CameraAppLaunch.candidatesFor(Params.of("lens" to "FRONT")),
        )
    }

    @Test
    fun `sends no lens extras when none was asked for`() {
        assertTrue(CameraAppLaunch.candidatesFor(Params.EMPTY).all { it.extras.isEmpty() })
    }

    @Test
    fun `rejects an unrecognised lens rather than silently ignoring it`() {
        val error = assertFailsWith<InvalidParamsException> {
            CameraAppLaunch.candidatesFor(Params.of("lens" to "periscope"))
        }

        assertTrue(error.message!!.contains("periscope"))
    }

    @Test
    fun `rejects an implausible package name`() {
        assertFailsWith<InvalidParamsException> {
            CameraAppLaunch.candidatesFor(Params.of("package" to "not a package name"))
        }
    }
}
