package com.camremote.app.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.camremote.app.di.AppContainer
import com.camremote.app.service.RemoteControlService

/**
 * The app's only screen, and it draws nothing.
 *
 * There is no dashboard, no switches, no token to read — the manifest gives this activity a fully
 * transparent theme, so the only thing a user ever sees is a native Android dialog (a runtime
 * permission request, or a system Settings screen), never anything drawn by this app. It exists
 * because Android has no other route to those dialogs: they can only be requested from an activity.
 *
 * It runs twice, in the same way each time:
 *
 * 1. **The first time the app is opened**, by tapping its icon. This is the one unavoidable manual
 *    step — no app can be started for the first time without it, on any Android device, by design.
 * 2. **On demand**, when a command needing a permission that turns out to be missing calls
 *    [com.camremote.core.port.PermissionPrompt.requestAttention] as part of failing. Android does
 *    not reliably let a background process pop an activity, so this attempt is best-effort; the
 *    guaranteed fallback is the agent's persistent notification, whose tap target is this same
 *    activity.
 *
 * Either way, a human is standing at the phone and about to see whatever system dialog Android
 * shows for the one thing most urgently missing — that is "a user attending to this on the Android
 * side", and it is the only interaction this project asks of them.
 *
 * It extends the plain [ComponentActivity] rather than `AppCompatActivity`: there is no AppCompat
 * widget on screen to justify pulling in that theme machinery, which is also why the whole
 * `androidx.appcompat`/Material dependency has been dropped from the app now that this was its last
 * caller.
 */
class LaunchActivity : ComponentActivity() {

    private val container by lazy { AppContainer.from(applicationContext) }

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestNextSettingsPermissionThenFinish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opening the app at all is what "the agent is meant to be running" means now that there is
        // no on/off switch to flip; BootReceiver reads this to decide whether to restart after a
        // reboot.
        container.config.isEnabled = true
        RemoteControlService.start(this)

        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            requestNextSettingsPermissionThenFinish()
        } else {
            requestRuntimePermissions.launch(missing.toTypedArray())
        }
    }

    /** CAMERA, and POST_NOTIFICATIONS where that is a runtime permission at all (API 33+). */
    private fun missingRuntimePermissions(): List<String> {
        val status = container.permissions.status()
        return buildList {
            if (!status.camera) add(Manifest.permission.CAMERA)
            if (!status.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Overlay and battery-exemption are not requestable dialogs — each has its own Settings screen,
     * and there is no way to know synchronously when the user has finished with one. Rather than
     * chain multiple Settings screens on top of each other, this opens at most one, prioritising the
     * overlay permission since it is what lets a *later* run of this very activity be launched from
     * the background at all. Whatever is still missing after that gets handled on the next attend.
     */
    private fun requestNextSettingsPermissionThenFinish() {
        val status = container.permissions.status()
        val packageUri = Uri.parse("package:$packageName")

        when {
            !status.canDrawOverlays ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            !status.ignoringBatteryOptimizations ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        }

        finish()
    }
}
