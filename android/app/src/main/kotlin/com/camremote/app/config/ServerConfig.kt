package com.camremote.app.config

import android.content.Context
import androidx.core.content.edit
import com.camremote.core.security.Tokens

/**
 * Persistent agent settings.
 *
 * The token is generated on first access rather than shipped in the build, so two installs of the
 * same APK do not share a secret. It lives in the app's private preferences, which on a
 * non-rooted device no other app can read.
 */
class ServerConfig(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The TCP port the agent listens on. */
    val port: Int get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)

    /**
     * Whether the user has switched the agent on.
     *
     * Off by default and never implicit: an app that quietly starts listening on the local network
     * the moment it is installed would be a poor citizen, whatever its purpose.
     */
    var isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_ENABLED, value) }

    /** The bearer token, minted on first use. */
    val token: String
        get() = preferences.getString(KEY_TOKEN, null) ?: regenerateToken()

    /** Invalidates every paired client. Offered on the setup screen for when a token leaks. */
    /** Mints a new token and stores it, which invalidates every machine that had paired. */
    fun regenerateToken(): String = Tokens.newToken().also { token ->
        preferences.edit { putString(KEY_TOKEN, token) }
    }

    private companion object {
        const val PREFERENCES_NAME = "cam-remote"
        const val KEY_PORT = "port"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "token"

        /** Above 1024 so no privilege is needed, and unlikely to collide with anything common. */
        const val DEFAULT_PORT = 8099
    }
}
