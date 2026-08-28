package com.camremote.core.logic

import com.camremote.core.port.ExtraValue
import com.camremote.core.port.LaunchSpec
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params

/**
 * Turns `camera.open` parameters into a [LaunchSpec].
 *
 * The lens hint is exactly that — a hint. There is no platform contract obliging a camera app to
 * honour it, and OEM apps vary, so both of the conventional extras are set and the command's
 * description says the behaviour is best-effort. The rear-camera *requirement* in the assignment is
 * met by `camera.capture`, which controls the sensor directly and does not depend on anyone's
 * goodwill.
 */
object CameraAppLaunch {

    /** The platform action for "open a camera app ready to take a still". */
    const val STILL_IMAGE_CAMERA_ACTION = "android.media.action.STILL_IMAGE_CAMERA"

    private const val EXTRA_CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
    private const val EXTRA_LENS_FACING_FRONT = "android.intent.extras.LENS_FACING_FRONT"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun specFor(params: Params): LaunchSpec = LaunchSpec(
        action = STILL_IMAGE_CAMERA_ACTION,
        targetPackage = params.optString("package")?.let(::validatePackage),
        newTask = true, // A service has no task of its own to launch into.
        extras = lensExtras(params.optString("lens")),
    )

    private fun lensExtras(lens: String?): Map<String, ExtraValue> {
        if (lens == null) return emptyMap()
        val front = when (lens.lowercase()) {
            "front" -> true
            "rear", "back" -> false
            else -> throw InvalidParamsException(
                "Parameter 'lens' must be 'front' or 'rear', got '$lens'",
            )
        }
        return mapOf(
            EXTRA_CAMERA_FACING to ExtraValue.IntValue(if (front) 1 else 0),
            EXTRA_LENS_FACING_FRONT to ExtraValue.BoolValue(front),
        )
    }

    private fun validatePackage(name: String): String {
        if (!PACKAGE_NAME.matches(name)) {
            throw InvalidParamsException("'$name' is not a valid package name")
        }
        return name
    }
}
