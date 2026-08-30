package com.camremote.app.config

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent agent settings.
 *
 * No secret lives here. The project assumes exactly one agent and one client share the LAN, so
 * there is nothing to authenticate and nothing to store beyond the port and whether the agent has
 * ever been started.
 */
class ServerConfig(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The TCP port the agent listens on. */
    val port: Int get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)

    /**
     * Whether the agent has ever been started.
     *
     * Set automatically the first time [com.camremote.app.setup.LaunchActivity] runs — there is no
     * on/off switch for the user to flip, since there is no screen to put one on. This flag exists
     * purely so [com.camremote.app.service.BootReceiver] knows whether to restart the agent after a
     * reboot: a fresh install that has never been opened once should stay quiet, not spring to life
     * the first time some *other* app triggers a reboot.
     */
    var isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_ENABLED, value) }

    private companion object {
        const val PREFERENCES_NAME = "cam-remote"
        const val KEY_PORT = "port"
        const val KEY_ENABLED = "enabled"

        /** Above 1024 so no privilege is needed, and unlikely to collide with anything common. */
        const val DEFAULT_PORT = 8099
    }
}
