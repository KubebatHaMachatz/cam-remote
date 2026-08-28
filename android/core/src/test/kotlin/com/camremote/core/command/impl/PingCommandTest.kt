package com.camremote.core.command.impl

import com.camremote.core.command.CommandOutcome.Success
import com.camremote.core.protocol.Params
import com.camremote.core.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class PingCommandTest {

    @Test
    fun `reports liveness and the device clock`() = runTest {
        val outcome = PingCommand(FakeClock(1_700_000_000_000)).execute(Params.EMPTY)

        val success = assertIs<Success>(outcome)
        assertEquals(JsonPrimitive(true), success.data?.get("pong"))
        assertEquals(JsonPrimitive(1_700_000_000_000), success.data?.get("deviceTimeMillis"))
    }

    @Test
    fun `describes itself for the discoverable catalog`() {
        assertEquals("system.ping", PingCommand(FakeClock()).descriptor.name)
    }
}
