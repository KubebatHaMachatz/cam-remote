package com.camremote.core.port

/**
 * A value describing an activity to launch, with no Android types in it.
 *
 * The point of this indirection is that choosing what to launch involves branching and is worth
 * testing, whereas turning this into an `Intent` and calling `startActivity` does not and is not.
 * The adapter that does the latter has no decisions left in it.
 */
data class LaunchSpec(
    val action: String,
    val targetPackage: String? = null,
    val newTask: Boolean = true,
    val categories: Set<String> = emptySet(),
    val extras: Map<String, ExtraValue> = emptyMap(),
    /**
     * A short name for the approach this spec represents, reported back to the client.
     *
     * Diagnostic rather than functional: across a fleet of handsets, knowing that the Samsung
     * answered `app_camera_category` while the realme answered `still_image_camera` is the
     * difference between "it works everywhere" and understanding why.
     */
    val strategy: String = "default",
)

/** The handful of intent-extra types this project needs, modelled without Android's Bundle. */
sealed interface ExtraValue {
    data class IntValue(val value: Int) : ExtraValue
    data class BoolValue(val value: Boolean) : ExtraValue
    data class TextValue(val value: String) : ExtraValue
}

/** Launches activities on the device. */
interface ActivityStarter {

    /**
     * The component that would handle [spec], or null when nothing on the device can.
     *
     * Resolving first turns "nothing happened" into a specific, reportable failure.
     */
    fun resolve(spec: LaunchSpec): String?

    /** @throws Exception when the platform refuses the launch. */
    fun start(spec: LaunchSpec)
}
