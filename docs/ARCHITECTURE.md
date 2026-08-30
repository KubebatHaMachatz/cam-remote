# Architecture

How the code is laid out, and the path a request takes through it. For *why* it is laid out this
way, see [DESIGN.md](DESIGN.md).

## The shape

```
                    ┌──────────────────────────────────────────┐
   Python CLI  ───► │            HTTP adapter (Ktor)           │   :app
                    └───────────────────┬──────────────────────┘
                                        │  CommandRequest
                    ┌───────────────────▼──────────────────────┐
                    │   CommandDispatcher   (driving port)     │
                    │   lookup → lock → timeout → map errors   │
                    ├──────────────────────────────────────────┤
                    │   CommandRegistry → Command × 7          │   :core
                    │   StatusCommand  GetPropCommand  …       │   no Android
                    ├──────────────────────────────────────────┤
                    │   Ports:  CameraController  PhotoStore   │
                    │           ActivityStarter  PermissionPrompt│
                    └───────────────────┬──────────────────────┘
                                        │  implemented by
                    ┌───────────────────▼──────────────────────┐
                    │  Adapters: CameraX · getprop · MediaStore│   :app
                    │            Intent                        │
                    └──────────────────────────────────────────┘
```

Dependencies point downward only. `:core` is a plain Kotlin/JVM module with no Android on its
classpath, so the boundary is enforced by the compiler rather than by discipline. There is no
authentication layer in this diagram because there is none in the app — see
[DESIGN.md §7](DESIGN.md#7-security) for the trade that makes.

## Modules

### `android/core` — plain Kotlin/JVM

| Package | Holds |
|---|---|
| `protocol` | The wire format: `CommandRequest`, `CommandResponse`, `CommandError`, `ErrorCode`, `Params`, `CommandDescriptor`, and `ProtocolJson` — the one place JSON is configured. |
| `command` | `Command`, `CommandRegistry`, `CommandDispatcher`, `ResourceLocks`, `DeviceResource`. |
| `command.impl` | The six capabilities: status, commands, getprop, open camera, camera-apps, capture. |
| `port` | What the core needs from the outside: `CameraController`, `PhotoStore`, `PropertyReader`, `ActivityStarter`, `PermissionInspector`, `PermissionPrompt`, `CommandLog`, `Clock`. |
| `logic` | Pure decisions worth testing: `CameraAppLaunch`, `CameraAppChoice`, `PhotoPaths`, `PhotoNaming`, `PhotoIndex`, `PropertyKeys`, `GetPropOutput`, `FirstAvailablePropertyReader`, `LanAddresses`. |

`src/testFixtures` holds the fakes — `FakeClock`, `TestCommand` — and is published to `:app` so the
transport tests drive real commands rather than re-inventing doubles.

### `android/app` — the Android application

| Package | Holds |
|---|---|
| `transport.http` | `commandApi` (the routes) and `HttpCommandServer` (the Ktor engine's lifetime). |
| `adapter` | Every port's Android implementation, plus `LocalAddresses`. |
| `service` | `RemoteControlService` — foreground service, `LifecycleOwner`, owner of the HTTP server — and `BootReceiver`. |
| `setup` | `LaunchActivity`, the app's only screen. Draws nothing of its own — see below. |
| `config` | `ServerConfig`: port and whether the agent has ever been started, in private preferences. |
| `di` | `AppContainer`: the whole composition, and the command catalog. |

### `python/camremote` — the control application

| Module | Holds |
|---|---|
| `cli.py` | Parser, address handling, exception-to-exit-code mapping. |
| `client.py` | `RemoteClient`: envelopes, error mapping, downloads. |
| `transport/` | `Transport` (the seam) and `HttpTransport` (urllib). |
| `commands/` | One module per verb, plus the `COMMANDS` registry. |
| `models.py`, `errors.py` | Wire-type mirrors and typed failures. |

## `LaunchActivity`, and how a permission gets granted

There is exactly one activity in the app, and it is not a dashboard — it draws nothing of its own,
because Android will not let a background service request a runtime permission or open a Settings
screen without *some* activity to host the dialog. `LaunchActivity`'s manifest theme is fully
transparent, so the only thing a user ever sees is the native dialog itself.

It is reached two ways, and does the same thing either way — check what is still missing, request
the first thing that is:

```
tap the app icon ─────────────────┐
                                   ├──► LaunchActivity ──► system permission dialog
command fails on a permission ────┘        │                or Settings screen
       (PermissionPrompt.requestAttention)  │
                                             ▼
                              guaranteed fallback: the agent's own
                              persistent notification retargets here
```

The icon tap is unavoidable — no Android app can be started for the first time any other way. Every
later prompt is triggered by whichever command first discovers something is missing, as part of that
command failing; `docs/DESIGN.md §7` covers why a direct background launch is only best-effort and
what the guaranteed fallback is.

## The path of a request

Taking `camremote take-picture --out ./shots` end to end:

1. **`cli.main`** parses the arguments and splits `--host` into an address and a port, accepting
   the `ip:port` form the agent's notification displays. There is nothing else to resolve: the
   address is required, so there is no discovery, no config file and no precedence order.
2. **`RemoteClient.invoke`** builds `{"id": …, "command": "camera.capture", "params": {…}}` with a
   fresh correlation id and hands it to the transport.
3. **`HttpTransport`** POSTs it to `/v1/command`. No credential travels with it — there is none.
4. **`commandApi`** parses the envelope and calls the dispatcher. It decides HTTP status codes and
   nothing else.
5. **`CommandDispatcher`** records the request through `CommandLog`, looks the command up, takes
   the camera mutex, and runs it under a 45-second budget. It records the outcome the same way,
   whatever that outcome is — the device log is the only account of what happened on a handset
   nobody is watching, and it hangs off the dispatcher rather than a transport so a second
   transport would inherit it.
6. **`CapturePhotoCommand`** checks the camera permission and that a rear sensor exists — calling
   `PermissionPrompt.requestAttention()` first if the permission is missing — asks `PhotoNaming` for
   a filename and `PhotoPaths` for a destination directory, both of which can still reject the
   request before the shutter fires, then calls `CameraController.captureRearStill` with a private
   scratch path.
7. **`CameraXController`** binds `ImageCapture` to the service's lifecycle with no preview, takes the
   photograph, and reads back the dimensions.
8. **`MediaStorePhotoStore.publish`** copies the scratch file into `Documents/cam-remote/` through
   MediaStore, mints an opaque id, and appends it to the persistent index. No storage permission is
   involved — see [DESIGN.md §8](DESIGN.md#8-storage).
9. The response travels back with a `downloadPath`, and the client **GETs `/v1/media/{id}`** and
   writes the JPEG next to the operator.

Every failure along that path becomes a typed `ErrorCode` with a human remediation, and everything
from step 5 to step 6 runs unchanged in unit tests with fakes in place of steps 7 and 8.

## Where each concern lives

Useful when deciding where a change belongs:

| Concern | Where |
|---|---|
| The wire format | `core/protocol/` — both sides depend on it |
| Whether a command may run at all | `CommandDispatcher` |
| What a command does | `core/command/impl/` |
| How the device does it | `app/adapter/` |
| Which HTTP status expresses a failure | `transport/http/CommandApi.kt` |
| What the device log says happened | `app/adapter/LogcatCommandLog.kt` |
| Which permission screen to show next | `app/setup/LaunchActivity.kt` |
| Where a photo may be written | `core/logic/PhotoPaths.kt` |
| What is wired to what | `app/di/AppContainer.kt` |
| What the CLI prints | `python/camremote/commands/` |

## Testing map

| Suite | Count | Runs where |
|---|---|---|
| `:core` unit tests | 177 | Desktop JVM, no Android, about a second |
| `:app` unit tests | 10 | Desktop JVM — the Ktor routes |
| Python unit tests | 57 | Desktop, standard library only |
| Instrumented | 7 | A real handset: real sensor, real MediaStore, real socket |

The `:app` count fell by ten when captures moved to MediaStore, and that is the boundary working
rather than coverage being lost. The photo store used to be a filesystem store and could be tested
on a desktop JVM; it now needs a `ContentResolver` and cannot. So the part with the edge cases —
the index: truncated lines, entries whose photo the user has deleted, compaction — was extracted
into `PhotoIndex` in `:core`, where it gained ten tests of its own, and `PhotoPaths` gained nine
more as the destination rules tightened. What is left in the adapter is `ContentResolver` calls with
no branching worth faking, covered by the instrumented suite.

Robolectric is deliberately **not** used anywhere. It was considered for exactly this case and
rejected: its MediaStore shadows do not implement scoped storage faithfully — `RELATIVE_PATH`,
`IS_PENDING` and volume semantics in particular — so such a test would assert against a fake that
behaves unlike any real device, and would pass while a handset failed. That is a worse outcome than
having the instrumented test be the only coverage, for the same reason the project does not fake
CameraX.
