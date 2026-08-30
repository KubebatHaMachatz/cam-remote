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
 * Either way, a human is standing at the phone and about to be walked through everything still
 * missing, one native dialog at a time — that is "a user attending to this on the Android side",
 * and it is the only interaction this project asks of them.
 *
 * It extends the plain [ComponentActivity] rather than `AppCompatActivity`: there is no AppCompat
 * widget on screen to justify pulling in that theme machinery, which is also why the whole
 * `androidx.appcompat`/Material dependency has been dropped from the app now that this was its last
 * caller.
 */
class LaunchActivity : ComponentActivity() {

    private val container by lazy { AppContainer.from(applicationContext) }

    /** Steps already offered during this launch. See [continueSetup] for why that is tracked. */
    private val offered = mutableSetOf<String>()

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            continueSetup()
        }

    /**
     * Returns here when a Settings screen closes.
     *
     * The result code carries nothing useful — both of these screens report `RESULT_CANCELED`
     * however the user leaves them — so what actually changed is re-read from the system instead.
     */
    private val openSettingsScreen =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            continueSetup()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(STATE_OFFERED)?.let(offered::addAll)

        // Opening the app at all is what "the agent is meant to be running" means now that there is
        // no on/off switch to flip; BootReceiver reads this to decide whether to restart after a
        // reboot.
        container.config.isEnabled = true
        RemoteControlService.start(this)

        continueSetup()
    }

    /** Survives a rotation or a low-memory kill mid-flow, so no step is offered twice. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_OFFERED, ArrayList(offered))
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
     * Offers the next thing still missing, and finishes once nothing is.
     *
     * Called on entry and again every time a dialog or Settings screen returns, so a fresh install
     * is walked through camera, notifications, "Appear on top" and the battery exemption in one
     * sitting. Each is re-read from the system rather than assumed, because the user is free to
     * decline any of them.
     *
     * `offered.add` returning false is the loop guard, and it is the reason this cannot become a
     * carousel: a step the user has declined stays declined for this launch, so the flow moves on
     * instead of reopening the same Settings screen forever. Anything left is picked up the next
     * time a command finds it missing and calls
     * [com.camremote.core.port.PermissionPrompt.requestAttention].
     *
     * Overlay comes before the battery exemption deliberately: it is what lets a *later* run of
     * this very activity be started from the background at all, so it is the one worth having if
     * the user only grants one.
     */
    private fun continueSetup() {
        val missing = missingRuntimePermissions()
        if (missing.isNotEmpty() && offered.add(STEP_RUNTIME)) {
            requestRuntimePermissions.launch(missing.toTypedArray())
            return
        }

        // Re-read after the dialogs above, not before: they are what most often changes it.
        val status = container.permissions.status()
        val packageUri = Uri.parse("package:$packageName")

        if (!status.canDrawOverlays && offered.add(STEP_OVERLAY)) {
            openSettingsScreen.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            return
        }
        if (!status.ignoringBatteryOptimizations && offered.add(STEP_BATTERY)) {
            openSettingsScreen.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            )
            return
        }

        finish()
    }

    private companion object {
        const val STATE_OFFERED = "offered"
        const val STEP_RUNTIME = "runtime"
        const val STEP_OVERLAY = "overlay"
        const val STEP_BATTERY = "battery"
    }
}
