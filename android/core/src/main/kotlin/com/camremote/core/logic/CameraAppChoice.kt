package com.camremote.core.logic

import com.camremote.core.port.ResolvedActivity

/**
 * Chooses which camera app to open when a device offers more than one.
 *
 * `PackageManager.resolveActivity` is the obvious call and the wrong one for a headless agent: given
 * two handlers and no user default it returns the system chooser, and starting a chooser puts a
 * dialog on a screen nobody is watching. So the handlers are enumerated, chosen between here, and
 * launched by explicit component.
 *
 * There is, for the record, no property to read instead. Vendors ship plenty of
 * `persist.vendor.camera.*` properties, but they configure the camera HAL rather than naming an app,
 * and none of them is a contract across manufacturers. Intent resolution is the mechanism Android
 * actually provides.
 */
object CameraAppChoice {

    /** Starting one of these asks a human to pick, which defeats the point of a remote agent. */
    private val CHOOSERS = setOf(
        "com.android.internal.app.ResolverActivity",
        "com.android.internal.app.ChooserActivity",
    )

    /**
     * The best handler, or null when there is none worth starting.
     *
     * Order of preference: the user's default, then a preinstalled camera, then whichever sorts
     * first. That last tie-break exists so two runs on the same device open the same app — a
     * non-deterministic choice here would produce a test that passes once and fails next time for no
     * visible reason.
     */
    fun pick(candidates: List<ResolvedActivity>): ResolvedActivity? = candidates
        .filterNot { it.activityName in CHOOSERS }
        .minWithOrNull(
            compareBy<ResolvedActivity> { if (it.isDefault) 0 else 1 }
                .thenBy { if (it.isSystem) 0 else 1 }
                .thenBy { it.component },
        )
}
