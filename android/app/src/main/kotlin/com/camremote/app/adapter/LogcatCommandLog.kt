package com.camremote.app.adapter

import android.util.Log
import com.camremote.core.port.CommandLog
import com.camremote.core.protocol.CommandRequest
import com.camremote.core.protocol.CommandResponse
import com.camremote.core.protocol.CommandStatus
import com.camremote.core.protocol.ErrorCode

/**
 * Writes every command and its outcome to logcat.
 *
 * The agent has no screen and its operator is not in the room, so this is the only account of what
 * the device actually did. It is deliberately readable by a human reading a log rather than shaped
 * for a parser — anything wanting structure has the JSON response.
 *
 *     adb logcat -s CamRemote
 *
 *     CamRemote  I  --> camera.capture  id=a1b2 params={"filename":"door"}
 *     CamRemote  I  <-- camera.capture  OK  in 2374ms  {"id":"kZ8…","path":"Documents/cam-remote/door.jpg",…}
 *     CamRemote  W  <-- camera.open     PRECONDITION_FAILED  in 4ms  Android will not let a background app …
 *     CamRemote  W      remediation: A settings prompt was shown on the device; grant "Display over other apps"
 *
 * Levels carry meaning, so a filtered log is still useful: a command that worked is `INFO`, one the
 * device refused is `WARN`, and a defect in the agent itself is `ERROR` — the last of those is the
 * only line here that should never appear.
 */
class LogcatCommandLog : CommandLog {

    /** Logged before the command runs, so a command that hangs still leaves a trace of arriving. */
    override fun received(request: CommandRequest) {
        val params = request.params.raw
        val suffix = if (params.isEmpty()) "" else "  params=$params"
        Log.i(TAG, "--> ${request.command}  id=${request.id}$suffix")
    }

    /** Logged once the outcome is known, whatever that outcome is. */
    override fun completed(request: CommandRequest, response: CommandResponse) {
        val took = "in ${response.durationMs}ms"

        if (response.status == CommandStatus.OK) {
            Log.i(TAG, "<-- ${request.command}  OK  $took  ${summarise(response)}")
            return
        }

        val error = response.error
        val line = "<-- ${request.command}  ${error?.code}  $took  ${error?.message}"
        // A defect in the agent is not the same event as a device that said no, and reading a log
        // at three in the morning is not the time to have to tell them apart by eye.
        if (error?.code == ErrorCode.INTERNAL) Log.e(TAG, line) else Log.w(TAG, line)

        error?.remediation?.let { Log.w(TAG, "    remediation: $it") }
    }

    /**
     * The response payload, truncated.
     *
     * A capture's payload is the interesting one — it names the file that now exists on the device,
     * which is the single most useful thing this log records. `system.commands` answers with the
     * whole catalog, which is not, so length is capped rather than special-cased per command: the
     * cap keeps a runaway payload from burying the next line, and logcat truncates long lines on
     * its own account anyway.
     */
    private fun summarise(response: CommandResponse): String {
        val data = response.data?.toString() ?: return "{}"
        return if (data.length <= MAX_PAYLOAD) data else data.take(MAX_PAYLOAD) + "…(truncated)"
    }

    private companion object {
        /** One tag for the whole command path, so `adb logcat -s CamRemote` is the whole story. */
        const val TAG = "CamRemote"
        const val MAX_PAYLOAD = 400
    }
}
