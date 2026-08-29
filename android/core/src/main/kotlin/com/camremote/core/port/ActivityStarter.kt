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
    /**
     * An explicit `package/activity` to launch, rather than letting the system choose.
     *
     * Set once the agent has picked from the available handlers, so a device with two camera apps
     * and no default cannot put a chooser dialog in front of nobody.
     */
    val component: String? = null,
)

/**
 * An activity that can handle a [LaunchSpec], and the two facts needed to choose between several.
 */
data class ResolvedActivity(
    val packageName: String,
    val activityName: String,
    /** Preinstalled on the system image, as opposed to sideloaded. */
    val isSystem: Boolean = false,
    /**
     * What the platform resolves this intent to.
     *
     * That is the user's chosen default when several apps compete, and simply "the only handler"
     * when one does -- so it means "the platform would pick this", not "a human chose it".
     */
    val isDefault: Boolean = false,
) {
    val component: String get() = "$packageName/$activityName"
}

/** The handful of intent-extra types this project needs, modelled without Android's Bundle. */
sealed interface ExtraValue {
    data class IntValue(val value: Int) : ExtraValue
    data class BoolValue(val value: Boolean) : ExtraValue
    data class TextValue(val value: String) : ExtraValue
}

/** Launches activities on the device. */
interface ActivityStarter {

    /**
     * Every activity that can handle [spec], newest-registered order as the platform reports it.
     *
     * All of them rather than just the best one, because choosing is a decision worth testing and
     * because listing them is what makes a new device's behaviour explicable from the control
     * machine. Empty means nothing on the device handles it, which is a specific, reportable failure
     * rather than a silent nothing-happened.
     */
    fun resolveAll(spec: LaunchSpec): List<ResolvedActivity>

    /** @throws Exception when the platform refuses the launch. */
    fun start(spec: LaunchSpec)
}
