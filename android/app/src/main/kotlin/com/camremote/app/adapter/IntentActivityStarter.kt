package com.camremote.app.adapter

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.camremote.core.port.ActivityStarter
import com.camremote.core.port.ExtraValue
import com.camremote.core.port.LaunchSpec

/**
 * Turns a [LaunchSpec] into an `Intent` and fires it.
 *
 * Every decision was made upstream in `CameraAppLaunch`, so this class has no branching worth
 * testing — which is the whole reason the split exists.
 *
 * Note the manifest's `<queries>` element: from API 30 package visibility hides other apps from
 * `resolveActivity`, and without declaring the camera intent there this would report "no camera app"
 * on any modern device.
 */
class IntentActivityStarter(private val context: Context) : ActivityStarter {

    override fun resolve(spec: LaunchSpec): String? {
        val info = context.packageManager
            .resolveActivity(spec.toIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        return "${info.activityInfo.packageName}/${info.activityInfo.name}"
    }

    override fun start(spec: LaunchSpec) {
        context.startActivity(spec.toIntent())
    }

    private fun LaunchSpec.toIntent(): Intent = Intent(action).also { intent ->
        targetPackage?.let(intent::setPackage)
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        extras.forEach { (key, value) ->
            when (value) {
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
                is ExtraValue.BoolValue -> intent.putExtra(key, value.value)
                is ExtraValue.TextValue -> intent.putExtra(key, value.value)
            }
        }
    }
}
