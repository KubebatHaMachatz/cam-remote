package com.camremote.core.port

/**
 * Surfaces a system permission or settings dialog on the device, on demand.
 *
 * This exists because the agent has no setup screen: the only moment a human is known to be looking
 * at the phone is *right after* a command has just failed for want of a permission. So rather than
 * ask the operator to go find a settings screen on their own, a command that hits a missing
 * permission calls [requestAttention] as part of executing — triggering the on-device dialog there
 * and then, before returning its (unchanged) failure to the remote caller. The remote client is told
 * to retry; the human standing at the phone taps Allow in the meantime.
 *
 * This is necessarily best-effort. Android does not let a background process pop an activity
 * unconditionally — [requestAttention] tries, and may be silently blocked by the platform depending
 * on OS version and how recently the app was interacted with. The always-available fallback is the
 * agent's own persistent notification, which is guaranteed to reach the same screen on tap.
 */
fun interface PermissionPrompt {
    /** Best-effort: attempts to bring the permission/settings screen to the foreground right now. */
    fun requestAttention()
}
