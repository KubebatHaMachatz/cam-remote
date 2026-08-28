package com.camremote.core.logic

import com.camremote.core.port.ExtraValue
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deciding *what* intent to fire is separable from firing it, and only the deciding half has any
 * branching in it. Keeping that half here means the Android adapter is a handful of lines with
 * nothing left to get wrong.
 */
class CameraAppLaunchTest {

    @Test
    fun `builds the standard still-image camera intent by default`() {
        val spec = CameraAppLaunch.specFor(Params.EMPTY)

        assertEquals("android.media.action.STILL_IMAGE_CAMERA", spec.action)
        assertNull(spec.targetPackage)
        // A service has no task of its own to launch into.
        assertTrue(spec.newTask)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun `targets a specific camera app when asked`() {
        val spec = CameraAppLaunch.specFor(Params.of("package" to "com.example.camera"))

        assertEquals("com.example.camera", spec.targetPackage)
    }

    @Test
    fun `hints the front lens when requested`() {
        val spec = CameraAppLaunch.specFor(Params.of("lens" to "front"))

        // Both spellings are sent because which one an OEM camera app honours is anyone's guess;
        // this is a hint, and the command's contract says so.
        assertEquals(ExtraValue.IntValue(1), spec.extras["android.intent.extras.CAMERA_FACING"])
        assertEquals(ExtraValue.BoolValue(true), spec.extras["android.intent.extras.LENS_FACING_FRONT"])
    }

    @Test
    fun `hints the rear lens when requested`() {
        val spec = CameraAppLaunch.specFor(Params.of("lens" to "rear"))

        assertEquals(ExtraValue.IntValue(0), spec.extras["android.intent.extras.CAMERA_FACING"])
        assertEquals(ExtraValue.BoolValue(false), spec.extras["android.intent.extras.LENS_FACING_FRONT"])
    }

    @Test
    fun `accepts back as a synonym for rear`() {
        assertEquals(
            CameraAppLaunch.specFor(Params.of("lens" to "rear")),
            CameraAppLaunch.specFor(Params.of("lens" to "back")),
        )
    }

    @Test
    fun `is case insensitive about the lens`() {
        assertEquals(
            CameraAppLaunch.specFor(Params.of("lens" to "front")),
            CameraAppLaunch.specFor(Params.of("lens" to "FRONT")),
        )
    }

    @Test
    fun `rejects an unrecognised lens rather than silently ignoring it`() {
        val error = assertFailsWith<InvalidParamsException> {
            CameraAppLaunch.specFor(Params.of("lens" to "periscope"))
        }

        assertTrue(error.message!!.contains("periscope"))
    }

    @Test
    fun `rejects an implausible package name`() {
        assertFailsWith<InvalidParamsException> {
            CameraAppLaunch.specFor(Params.of("package" to "not a package name"))
        }
    }
}
