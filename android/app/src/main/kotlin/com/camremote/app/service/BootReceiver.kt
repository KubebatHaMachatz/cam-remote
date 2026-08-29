package com.camremote.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.camremote.app.di.AppContainer

/**
 * Brings the agent back after a reboot, if the user had left it switched on.
 *
 * A headless agent that stops answering because the phone restarted overnight is not much of an
 * agent. Starting a camera-typed foreground service from a boot broadcast is precisely the case
 * Android 14 restricts, which the "Display over other apps" grant from setup exempts us from — and
 * if that grant is missing, the service still starts without the camera type and reports the
 * problem through `system.status` rather than failing silently.
 */
class BootReceiver : BroadcastReceiver() {

    /** Restarts the agent after a reboot, but only if the user had left it switched on. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppContainer.from(context).config.isEnabled) return

        Log.i(TAG, "Restarting the agent after boot")
        runCatching { RemoteControlService.start(context) }
            .onFailure { Log.w(TAG, "Could not restart the agent after boot", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
