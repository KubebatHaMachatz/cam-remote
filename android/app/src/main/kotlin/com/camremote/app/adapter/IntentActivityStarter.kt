package com.camremote.app.adapter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.ExtraValue
import com.camremote.core.port.LaunchSpec
import com.camremote.core.port.ResolvedActivity

/**
 * Turns a [LaunchSpec] into an `Intent` and fires it.
 *
 * Every decision was made upstream in `CameraAppLaunch`, so this class has no branching worth
 * testing — which is the whole reason the split exists.
 *
 * Note the manifest's `<queries>` element: from API 30 package visibility hides other apps from
 * both `resolveActivity` and `queryIntentActivities`, and without declaring each camera intent there
 * this would report "no camera app" on any modern device.
 */
class IntentActivityStarter(private val context: Context) : ActivityStarter {

    override fun resolveAll(spec: LaunchSpec): List<ResolvedActivity> {
        val intent = spec.toIntent()
        val manager = context.packageManager

        // MATCH_DEFAULT_ONLY tells us whether the user has picked a default, which is worth knowing
        // even though the choice itself is made in :core.
        val default = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.let { "${it.packageName}/${it.name}" }

        return manager.queryIntentActivities(intent, 0).map { resolved ->
            val activity = resolved.activityInfo
            ResolvedActivity(
                packageName = activity.packageName,
                activityName = activity.name,
                isSystem = activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                isDefault = "${activity.packageName}/${activity.name}" == default,
            )
        }
    }

    override fun start(spec: LaunchSpec) {
        context.startActivity(spec.toIntent())
    }

    private fun LaunchSpec.toIntent(): Intent = Intent(action).also { intent ->
        targetPackage?.let(intent::setPackage)
        // Naming the activity outright is what keeps the system chooser out of the picture.
        component?.split('/', limit = 2)?.let { (pkg, name) -> intent.setClassName(pkg, name) }
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        categories.forEach(intent::addCategory)
        extras.forEach { (key, value) ->
            when (value) {
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
                is ExtraValue.BoolValue -> intent.putExtra(key, value.value)
                is ExtraValue.TextValue -> intent.putExtra(key, value.value)
            }
        }
    }
}
