package com.camremote.core.logic

import com.camremote.core.port.ResolvedActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Picking which camera app to open, when a device offers more than one.
 *
 * `PackageManager.resolveActivity` is the obvious call and the wrong one here: with two handlers and
 * no user default it returns the system chooser, and launching a chooser from a headless agent puts
 * a dialog on screen that nobody is present to answer. So the agent enumerates the handlers, decides
 * for itself, and launches by explicit component.
 */
class CameraAppChoiceTest {

    private fun activity(
        packageName: String,
        activityName: String = ".Camera",
        system: Boolean = false,
        default: Boolean = false,
    ) = ResolvedActivity(packageName, activityName, isSystem = system, isDefault = default)

    @Test
    fun `picks the only handler`() {
        val only = activity("com.oplus.camera", system = true)

        assertEquals(only, CameraAppChoice.pick(listOf(only)))
    }

    @Test
    fun `never picks the system chooser`() {
        // Starting this shows a "which app?" dialog on a device with nobody looking at it.
        val chooser = activity("android", "com.android.internal.app.ResolverActivity")
        val camera = activity("com.sec.android.app.camera", system = true)

        assertEquals(camera, CameraAppChoice.pick(listOf(chooser, camera)))
    }

    @Test
    fun `also rejects the newer chooser activity`() {
        val chooser = activity("android", "com.android.internal.app.ChooserActivity")

        assertNull(CameraAppChoice.pick(listOf(chooser)))
    }

    @Test
    fun `respects the user's chosen default`() {
        val preinstalled = activity("com.sec.android.app.camera", system = true)
        val chosen = activity("com.example.opencamera", default = true)

        // If someone has set a default camera on the device, that is the answer, system app or not.
        assertEquals(chosen, CameraAppChoice.pick(listOf(preinstalled, chosen)))
    }

    @Test
    fun `prefers a preinstalled camera over a sideloaded one`() {
        val preinstalled = activity("com.sec.android.app.camera", system = true)
        val thirdParty = activity("com.example.opencamera")

        // With no default set, "the device's camera" means the one the device shipped with.
        assertEquals(preinstalled, CameraAppChoice.pick(listOf(thirdParty, preinstalled)))
    }

    @Test
    fun `is deterministic when nothing distinguishes the candidates`() {
        val first = activity("com.a.camera")
        val second = activity("com.b.camera")

        // Two runs on the same device must open the same app, or a test that passes once fails next
        // time for no visible reason.
        assertEquals(
            CameraAppChoice.pick(listOf(first, second)),
            CameraAppChoice.pick(listOf(second, first)),
        )
    }

    @Test
    fun `answers nothing when the device has no camera app`() {
        assertNull(CameraAppChoice.pick(emptyList()))
    }
}
