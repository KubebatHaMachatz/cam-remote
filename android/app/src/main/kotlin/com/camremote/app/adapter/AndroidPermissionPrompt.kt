package com.camremote.app.adapter

import android.content.Context
import android.content.Intent
import android.util.Log
import com.camremote.app.LOG_TAG_PREFIX
import com.camremote.app.setup.LaunchActivity
import com.camremote.core.port.PermissionPrompt

/**
 * Tries to bring [LaunchActivity] to the foreground from wherever a command is running.
 *
 * A best-effort attempt, not a guarantee: Android does not reliably let a background service start
 * an activity, and whether this succeeds depends on the OS version and how recently the app was
 * interacted with. Failure is swallowed rather than surfaced — a command whose permission check
 * already failed must still return its own (unrelated) error, not a new one about the prompt.
 * The agent's persistent notification, which targets the same activity, is the guaranteed fallback.
 */
class AndroidPermissionPrompt(private val context: Context) : PermissionPrompt {

    override fun requestAttention() {
        runCatching {
            context.startActivity(
                Intent(context, LaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Log.i(TAG, "Could not bring the permission prompt to the foreground directly", it)
        }
    }

    private companion object {
        const val TAG = "$LOG_TAG_PREFIX:AndroidPermissionPrompt"
    }
}
