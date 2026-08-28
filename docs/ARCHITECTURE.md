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
                    │   CommandRegistry → Command × 6          │   :core
                    │   PingCommand  GetPropCommand  …         │   no Android
                    ├──────────────────────────────────────────┤
                    │   Ports:  CameraController  PhotoStore   │
                    │           PropertyReader   ActivityStarter│
                    └───────────────────┬──────────────────────┘
                                        │  implemented by
                    ┌───────────────────▼──────────────────────┐
                    │  Adapters: CameraX · getprop · files ·   │   :app
                    │            Intent · NsdManager           │
                    └──────────────────────────────────────────┘
```

Dependencies point downward only. `:core` is a plain Kotlin/JVM module with no Android on its
classpath, so the boundary is enforced by the compiler rather than by discipline.

## Modules

### `android/core` — plain Kotlin/JVM

| Package | Holds |
|---|---|
| `protocol` | The wire format: `CommandRequest`, `CommandResponse`, `CommandError`, `ErrorCode`, `Params`, `CommandDescriptor`, and `ProtocolJson` — the one place JSON is configured. |
| `command` | `Command`, `CommandRegistry`, `CommandDispatcher`, `ResourceLocks`, `DeviceResource`. |
| `command.impl` | The six capabilities: ping, status, commands, getprop, open camera, capture. |
| `port` | What the core needs from the outside: `CameraController`, `PhotoStore`, `PropertyReader`, `ActivityStarter`, `PermissionInspector`, `Clock`. |
| `logic` | Pure decisions worth testing: `CameraAppLaunch`, `PhotoPaths`, `PhotoNaming`, `PropertyKeys`, `GetPropOutput`, `FirstAvailablePropertyReader`. |
| `security` | `AccessControl`, `PairingWindow`, `Tokens`. |

`src/testFixtures` holds the fakes — `FakeClock`, `TestCommand` — and is published to `:app` so the
transport tests drive real commands rather than re-inventing doubles.

### `android/app` — the Android application

| Package | Holds |
|---|---|
| `transport.http` | `commandApi` (the routes) and `HttpCommandServer` (the Ktor engine's lifetime). |
| `adapter` | Every port's Android implementation, plus `NsdServiceAdvertiser` and `GalleryPublisher`. |
| `service` | `RemoteControlService` — foreground service, `LifecycleOwner`, owner of server and advertiser — and `BootReceiver`. |
| `setup` | `SetupActivity`, the app's only screen, and `LocalAddresses`. |
| `config` | `ServerConfig`: port, token, on/off, in private preferences. |
| `di` | `AppContainer`: the whole composition, and the command catalog. |

### `python/camremote` — the control application

| Module | Holds |
|---|---|
| `cli.py` | Parser, agent resolution, exception-to-exit-code mapping. |
| `client.py` | `RemoteClient`: envelopes, error mapping, downloads, pairing. |
| `transport/` | `Transport` (the seam) and `HttpTransport` (urllib). |
| `discovery/mdns.py` | A minimal DNS-SD browser. |
| `commands/` | One module per verb, plus the `COMMANDS` registry. |
| `config.py`, `models.py`, `errors.py` | Config precedence, wire-type mirrors, typed failures. |

## The path of a request

Taking `camremote take-picture --out ./shots` end to end:

1. **`cli.main`** parses the arguments and resolves which agent to talk to — an explicit `--host`,
   else the environment, else `~/.camremote.toml`, else mDNS discovery.
2. **`RemoteClient.invoke`** builds `{"id": …, "command": "camera.capture", "params": {…}}` with a
   fresh correlation id and hands it to the transport.
3. **`HttpTransport`** POSTs it to `/v1/command` with the bearer token.
4. **`commandApi`** authenticates, parses the envelope, and calls the dispatcher. It decides HTTP
   status codes and nothing else.
5. **`CommandDispatcher`** looks the command up, takes the camera mutex, and runs it under a
   45-second budget.
6. **`CapturePhotoCommand`** checks the camera permission and that a rear sensor exists, asks
   `PhotoNaming` for a filename and `PhotoStore` for a destination — which is where the path is
   confined to the allow-list — then calls `CameraController.captureRearStill`.
7. **`CameraXController`** binds `ImageCapture` to the service's lifecycle with no preview, takes the
   photograph, and reads back the dimensions.
8. **`FileSystemPhotoStore.record`** mints an opaque id and appends it to the persistent index.
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
| What is wired to what | `app/di/AppContainer.kt` |
| What the CLI prints | `python/camremote/commands/` |

## Testing map

| Suite | Count | Runs where |
|---|---|---|
| `:core` unit tests | 119 | Desktop JVM, no Android, about a second |
| `:app` unit tests | 26 | Desktop JVM — Ktor routes and the filesystem store |
| Python unit tests | 65 | Desktop, standard library only |
| Instrumented | 5 | A real handset: real sensor, real property store, real socket |
