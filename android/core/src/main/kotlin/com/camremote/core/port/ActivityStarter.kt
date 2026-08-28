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
    val extras: Map<String, ExtraValue> = emptyMap(),
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
