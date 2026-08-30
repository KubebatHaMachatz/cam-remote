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
        StatusCommand(permissions, ::deviceDescription, camera, clock),
        …
        CapturePhotoCommand(camera, photos, permissions, clock, permissionPrompt),
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
COMMANDS = (
    STATUS, LIST_COMMANDS, GETPROP,
    OPEN_CAMERA, CAMERA_APPS, TAKE_PICTURE, DEVICE_REPORT, REBOOT,
)
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
`PhotoStore` a SAF-backed one that writes wherever the user picked with `ACTION_OPEN_DOCUMENT_TREE`,
give `ActivityStarter` a no-op for testing. Each is one class and one line in `AppContainer`.

---

## Adding authentication

There is none, deliberately. The agent assumes one app and one client on a trusted LAN — the
assignment's own framing — and `docs/DESIGN.md` §7 argues the trade rather than hiding it. It is
also the **first thing to change for any broader use**, so this is what that costs.

The short version: it is confined to the transport, because that is where it belongs.

### The agent side

A shared secret belongs with the rest of the agent's settings, generated once:

```kotlin
// ServerConfig.kt
val token: String
    get() = prefs.getString(KEY_TOKEN, null) ?: newToken().also {
        prefs.edit().putString(KEY_TOKEN, it).apply()
    }

/** 256 bits from a CSPRNG. Not a UUID: those are not required to be unpredictable. */
private fun newToken(): String = ByteArray(32)
    .also(SecureRandom()::nextBytes)
    .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
```

Then one interceptor in `commandApi`, which already takes its dependencies by parameter:

```kotlin
fun Application.commandApi(
    dispatcher: CommandDispatcher,
    photos: PhotoStore,
    device: DeviceDescription,
    token: String,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        // /v1/health stays open: discovery has to be able to identify an agent before it can
        // possibly know its secret.
        if (call.request.path() == "/v1/health") return@intercept

        val presented = call.request.header(HttpHeaders.Authorization)
            ?.removePrefix("Bearer ")
            .orEmpty()

        // Constant-time. A naive == leaks the token one character at a time to anyone willing to
        // measure, and on a LAN they can measure.
        if (!MessageDigest.isEqual(presented.toByteArray(), token.toByteArray())) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, …)
            finish()
        }
    }
    …
}
```

Three things that are easy to get wrong:

- **`/v1/media/{id}` must be covered too.** It is the route that hands out photographs, and it is the
  one people forget because it is not the command route. Intercepting the pipeline rather than
  decorating one handler is what makes forgetting impossible.
- **Compare in constant time.** `MessageDigest.isEqual`, not `==`.
- **Restore `ErrorCode.UNAUTHORIZED`.** It was removed with the auth layer; protocol failures use
  HTTP status *and* a typed code, and the Python client maps 401 back to a typed failure.

### The client side

One header in `HttpTransport`, and a `--token` alongside `--host`:

```python
if self._token:
    request.add_header("Authorization", f"Bearer {self._token}")
```

A secret typed on every command line is worse than one stored, so this is the point at which the
client would want a config file again. It had one — `~/.camremote.toml`, written by a `pair` verb —
and it was removed with mDNS discovery, because remembering an address is not worth a file when the
address is required anyway. A secret is a better reason for that file than an address ever was; the
deleted `config.py` is in the history, and it chmod'd the file to 0600 for exactly this case.

### How the secret gets there

This is the genuinely hard part, and the reason the project does not have one: with no adb in the
product and no UI, there is nowhere to display a token and nothing to type it into. Three answers,
in ascending order of effort:

1. **Show it in the persistent notification**, which already exists and already displays the
   address. Read it off the phone once.
2. **A pairing window**: a `POST /v1/pair` that returns the token only during a short interval the
   user opens from the device. This is what the project did before `v1`, and the git history has the
   whole implementation if it is wanted back.
3. **Trust on first use**: the agent accepts the first client it ever sees and pins it. No user
   interaction at all, and a meaningful weakening on a hostile network.

### What does not change

`:core`, every command, the dispatcher, the Python command modules, and the CLI. Authentication is a
property of a transport, not of the application, and the module boundary makes that a fact about the
build graph rather than a promise. An MQTT transport added later brings its own credentials and
none of this applies to it.

---

## Where the seams are

| To change… | Touch | Leave alone |
|---|---|---|
| What a command does | `core/command/impl/` | Everything else |
| How the device does it | `app/adapter/` | The command |
| How commands arrive | `app/transport/` | The dispatcher, the commands |
| Who may issue a command | `app/transport/http/CommandApi.kt` | `:core`, every command |
| Which failures mean what | `core/protocol/ErrorCode` | The transports |
| What the CLI prints | `python/camremote/commands/` | The agent |
| What is wired to what | `app/di/AppContainer.kt` | The classes being wired |
