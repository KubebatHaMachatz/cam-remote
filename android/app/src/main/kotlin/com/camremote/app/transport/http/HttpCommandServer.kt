package com.camremote.app.transport.http

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * Owns the embedded server's lifetime.
 *
 * Split from the routing so that the routes stay testable with `testApplication` and nothing here
 * needs a test of its own — this class is start, stop, and the choice of bind address.
 *
 * It binds every interface, because the entire point is that a control machine elsewhere on the
 * Wi-Fi can reach it. That exposure is why the token is mandatory, why the server only runs while
 * the user has switched it on, and why the README says plainly what joining an untrusted network
 * with this running would mean.
 */
class HttpCommandServer(
    private val port: Int,
    private val configure: Application.() -> Unit,
) {

    private var engine: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = engine != null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = BIND_ALL_INTERFACES) { configure() }
            .start(wait = false)
    }

    fun stop() {
        engine?.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
        engine = null
    }

    private companion object {
        const val BIND_ALL_INTERFACES = "0.0.0.0"
        const val GRACE_MILLIS = 500L
        const val TIMEOUT_MILLIS = 2_000L
    }
}
