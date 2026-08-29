package com.camremote.app.adapter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.camremote.core.port.PermissionInspector
import com.camremote.core.protocol.PermissionStatus

/**
 * Reads the current state of the four grants the agent depends on.
 *
 * Kept as one adapter so that `system.status` can answer "why did that not work?" over the network,
 * which matters a great deal for a device that has no control UI of its own.
 */
class AndroidPermissionInspector(private val context: Context) : PermissionInspector {

    /** Reads all four grants fresh, since the user may change any of them at any time. */
    override fun status() = PermissionStatus(
        camera = isGranted(Manifest.permission.CAMERA),
        notifications = areNotificationsAllowed(),
        canDrawOverlays = Settings.canDrawOverlays(context),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
    )

    /** Whether a normal runtime permission is held. */
    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether the foreground-service notification may be shown. */
    private fun areNotificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Before API 33 there was no runtime permission; the notification is allowed unless the
            // user turned the channel off, which the foreground service does not depend on.
            true
        }

    /** Whether the agent is exempt from Doze, which decides if it answers with the screen off. */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService<PowerManager>()
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
}
