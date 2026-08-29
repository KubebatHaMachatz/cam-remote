package com.camremote.core.logic

import com.camremote.core.port.ExtraValue
import com.camremote.core.port.LaunchSpec
import com.camremote.core.protocol.InvalidParamsException
import com.camremote.core.protocol.Params

/**
 * Works out the ordered list of ways to open a camera app.
 *
 * There is no single intent every Android device answers. `STILL_IMAGE_CAMERA` is the semantically
 * correct one and is what ColorOS answers, but OEM builds vary and bare AOSP system images often
 * declare none of them — some ship no camera app at all. So the command is given candidates to try
 * in order rather than one intent to fire and hope for.
 *
 * The lens hint is exactly that — a hint. No platform contract obliges a camera app to honour it,
 * and OEM apps vary, so both conventional extras are set on every candidate and the command's
 * description says the behaviour is best-effort. The rear-camera *requirement* in the assignment is
 * met by `camera.capture`, which drives the sensor directly and depends on nobody's goodwill.
 */
object CameraAppLaunch {

    /** "Open a camera app ready to take a still." The most semantically correct choice. */
    const val STILL_IMAGE_CAMERA_ACTION = "android.media.action.STILL_IMAGE_CAMERA"

    /** "Take a picture and hand it back." Widely declared, but see the ordering note below. */
    const val IMAGE_CAPTURE_ACTION = "android.media.action.IMAGE_CAPTURE"

    private const val MAIN_ACTION = "android.intent.action.MAIN"
    private const val CATEGORY_APP_CAMERA = "android.intent.category.APP_CAMERA"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    private const val EXTRA_CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
    private const val EXTRA_LENS_FACING_FRONT = "android.intent.extras.LENS_FACING_FRONT"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * Candidates in the order they should be attempted.
     *
     * Ordering rationale:
     * 1. `STILL_IMAGE_CAMERA` — means precisely "open the camera app".
     * 2. `MAIN` + `APP_CAMERA` — the category a launcher uses to find the camera; a plain launch,
     *    very widely declared, and correct on builds that skip the action above.
     * 3. `IMAGE_CAPTURE` — last, because it puts the app into "take one and return it" mode.
     *    Started from a service with no result receiver, some camera apps sit on a confirm screen
     *    with nowhere to return to. Fine as a fallback, wrong as a first choice.
     * 4. `MAIN` + `LAUNCHER` — only when the caller named a package, as a last resort for an app
     *    that declares none of the camera intents. Unscoped it would open the device's home screen.
     */
    fun candidatesFor(params: Params): List<LaunchSpec> {
        val targetPackage = params.optString("package")?.let(::validatePackage)
        val extras = lensExtras(params.optString("lens"))

        /** One candidate, carrying the caller's package and lens choices. */
        fun spec(strategy: String, action: String, categories: Set<String> = emptySet()) = LaunchSpec(
            action = action,
            targetPackage = targetPackage,
            newTask = true, // A service has no task of its own to launch into.
            categories = categories,
            extras = extras,
            strategy = strategy,
        )

        return buildList {
            add(spec("still_image_camera", STILL_IMAGE_CAMERA_ACTION))
            add(spec("app_camera_category", MAIN_ACTION, setOf(CATEGORY_APP_CAMERA)))
            add(spec("image_capture", IMAGE_CAPTURE_ACTION))
            if (targetPackage != null) {
                add(spec("launcher_entry", MAIN_ACTION, setOf(CATEGORY_LAUNCHER)))
            }
        }
    }

    /**
     * The extras that hint at a lens, or none when the caller expressed no preference.
     *
     * @throws InvalidParamsException on an unrecognised value, rather than silently ignoring it —
     *   a typo that quietly does nothing is worse than one that complains.
     */
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

    /** @throws InvalidParamsException if [name] is not a plausible Android package name. */
    private fun validatePackage(name: String): String {
        if (!PACKAGE_NAME.matches(name)) {
            throw InvalidParamsException("'$name' is not a valid package name")
        }
        return name
    }
}
