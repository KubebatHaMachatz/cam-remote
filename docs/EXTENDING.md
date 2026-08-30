# Extending cam-remote

Three worked examples, in order of how often you are likely to need them.

---

## Adding a command

The assignment's real test: adding a capability should barely touch what already exists. Here is
`device.reboot`, end to end.

### 1. Write the command — one new file

`android/core/src/main/kotlin/com/camremote/core/command/impl/RebootCommand.kt`:

```kotlin
package com.camremote.core.command.impl

import com.camremote.core.command.Command
import com.camremote.core.command.CommandOutcome
import com.camremote.core.port.DeviceRebooter
import com.camremote.core.protocol.CommandDescriptor
import com.camremote.core.protocol.ErrorCode
import com.camremote.core.protocol.ParameterDescriptor
import com.camremote.core.protocol.ParameterType
import com.camremote.core.protocol.Params
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Restarts the device. */
class RebootCommand(private val rebooter: DeviceRebooter) : Command {

    override val descriptor = CommandDescriptor(
        name = "device.reboot",
        description = "Restart the device.",
        parameters = listOf(
            ParameterDescriptor(
                name = "mode",
                type = ParameterType.STRING,
                required = false,
                description = "'recovery' or 'bootloader'. Omit for a normal restart.",
            ),
        ),
    )

    override suspend fun execute(params: Params): CommandOutcome {
        val mode = params.optString("mode")
        return if (rebooter.reboot(mode)) {
            CommandOutcome.Success(buildJsonObject { put("rebooting", JsonPrimitive(true)) })
        } else {
            CommandOutcome.failure(
                code = ErrorCode.PERMISSION_DENIED,
                message = "Rebooting requires a system-signed app",
                remediation = "Install cam-remote as a privileged app, or reboot the device by hand",
            )
        }
    }
}
```

### 2. Register it — one line

In `android/app/src/main/kotlin/com/camremote/app/di/AppContainer.kt`:

```kotlin
    private fun commands(camera: CameraController, descriptors: …): List<Command> = listOf(
        PingCommand(clock),
        …
        CapturePhotoCommand(camera, photos, permissions, clock),
        RebootCommand(AndroidDeviceRebooter(context)),   // ← added
    )
```

**That is the whole change.** No route, no transport, no protocol edit, no client release. The
command appears in `system.commands` immediately, so an existing `camremote commands` lists it, and
`camremote --json` can invoke it. The CLI verb below is optional sugar.

### 3. Test it first, of course

`android/core/src/test/kotlin/.../RebootCommandTest.kt`, against a fake port:

```kotlin
@Test
fun `reports a device that will not let an app reboot it`() = runTest {
    val outcome = RebootCommand(DeviceRebooter { false }).execute(Params.EMPTY)

    assertEquals(ErrorCode.PERMISSION_DENIED, assertIs<Failure>(outcome).error.code)
}
```

Runs on a desktop JVM in milliseconds, because nothing about the decision touches Android.

### 4. Optionally, a CLI verb

`python/camremote/commands/reboot.py`:

```python
def _run(context):
    response = context.agent.invoke("device.reboot", {"mode": context.args.mode} if context.args.mode else None)
    context.emit(response.data, "The device is restarting.")
    return 0


REBOOT = CliCommand(
    name="reboot",
    help="Restart the device.",
    run=_run,
    add_arguments=lambda parser: parser.add_argument("--mode", choices=("recovery", "bootloader")),
)
```

and one line in `python/camremote/commands/__init__.py`:

```python
COMMANDS = (DISCOVER, PAIR, STATUS, PING, LIST_COMMANDS, GETPROP, OPEN_CAMERA, TAKE_PICTURE, REBOOT)
```

### Checklist

| Step | Files touched |
|---|---|
| The command | 1 new |
| Its test | 1 new |
| Register it in the agent | 1 line |
| A port, if it needs a new capability | 1 new interface + 1 new adapter |
| A CLI verb (optional) | 1 new + 1 line |

Nothing existing is modified beyond two registry lines.

---

## Adding a transport

The agent's command layer knows nothing about HTTP. `CommandDispatcher` is the driving port, and
anything that can produce a `CommandRequest` and deliver a `CommandResponse` can drive it.

This is also the answer to "how do I control the phone from outside the local network?" — see the
alternatives weighed in [DESIGN.md](DESIGN.md#2-how-the-device-is-controlled).

### What a transport has to do

```kotlin
class MqttCommandTransport(
    private val dispatcher: CommandDispatcher,
    private val deviceId: String,
) {
    /** Subscribe to camremote/<deviceId>/cmd; publish replies to camremote/<deviceId>/res. */
    suspend fun onMessage(payload: String) {
        val request = ProtocolJson.decodeRequest(payload)     // same envelope
        val response = dispatcher.dispatch(request)           // same dispatcher
        publish("camremote/$deviceId/res", ProtocolJson.encodeResponse(response))
    }
}
```

Note what is *not* there: no command knowledge, no permission logic, no timeout policy. Those belong
to the dispatcher and stay there. A transport decodes, dispatches, and encodes — nothing else.

### Where it plugs in

1. Write the class in `app/transport/<name>/`.
2. Start it alongside the HTTP server in `RemoteControlService.startServer()`.
3. Leave everything else alone.

The `id` field in the envelope exists for exactly this: a message bus is not request/response, so
replies have to be paired with requests by correlation id.

### One thing a non-HTTP transport must solve

- **Large payloads.** A full-resolution JPEG is several megabytes, which is fine over an HTTP `GET`
  and awkward over a message broker. Either chunk it, or keep `/v1/media/{id}` for the bytes and use
  the broker only for commands.

There is nothing to say about authentication here because the agent has none — see
[DESIGN.md §7](DESIGN.md#7-security) for that trade. If a future version reintroduces a credential,
the right seam is the same one: a transport-agnostic check the dispatcher's caller runs before
`dispatch()`, so every transport enforces it identically rather than each rolling its own.

### On the client side

Implement `camremote.transport.base.Transport` — three methods — and hand it to `RemoteClient`. The
CLI takes a `connect` callable precisely so the transport can be swapped without touching any verb.

---

## Swapping an implementation

Every port can be replaced without its callers noticing. `PropertyReader` is the worked example,
because the project already ships two implementations of it.

```kotlin
fun interface PropertyReader {
    fun read(key: String): String?
}
```

- `ExecGetPropReader` runs `/system/bin/getprop`. Works almost everywhere; no permission, no root.
- `SystemPropertiesReader` reflects on the hidden `android.os.SystemProperties`. Blocked by
  hidden-API restrictions on many modern builds.

Neither works on every device, so `FirstAvailablePropertyReader` tries them in order:

```kotlin
private val properties by lazy {
    FirstAvailablePropertyReader(listOf(ExecGetPropReader(), SystemPropertiesReader()))
}
```

The distinction that matters — and that the tests pin down — is that a reader returning `null` has
*answered*: the property is not set, and the chain stops. Only a thrown exception means "this
mechanism does not work here", which is the case worth retrying with another.

To add a third, write the class, put it in the list, and write a test with the other two as fakes.
`GetPropCommand` does not change, because it never knew which one it was talking to.

The same shape applies to the rest: give `CameraController` a Camera2 implementation, give
`PhotoStore` a MediaStore-only one, give `ActivityStarter` a no-op for testing. Each is one class and
one line in `AppContainer`.

---

## Where the seams are

| To change… | Touch | Leave alone |
|---|---|---|
| What a command does | `core/command/impl/` | Everything else |
| How the device does it | `app/adapter/` | The command |
| How commands arrive | `app/transport/` | The dispatcher, the commands |
| Which failures mean what | `core/protocol/ErrorCode` | The transports |
| What the CLI prints | `python/camremote/commands/` | The agent |
| What is wired to what | `app/di/AppContainer.kt` | The classes being wired |
