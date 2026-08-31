# Code tour — the Android agent

A reading order for the `android/` project, for someone opening it for the first time. About 3,600
lines of Kotlin across two modules; this walks the spine of it in the order that makes each file
make sense when you reach it.

[ARCHITECTURE.md](ARCHITECTURE.md) is the map. This is the walk.

## Before you open anything

Two facts explain most of the layout.

**There is no UI.** The app has one Activity that draws nothing at all. So nothing you are about to
read is presentation code, and patterns that assume a screen — MVVM and friends — are absent on
purpose.

**There are two modules, and the split is enforced by the compiler.** `:core` is a plain Kotlin/JVM
module with no Android on its classpath. `:app` is the Android application. Anything Android —
CameraX, MediaStore, `Intent`, `Context` — physically cannot be referenced from `:core`. That is not
a convention anyone has to remember; it is a build-graph fact, and it is why almost every decision
in the project ended up somewhere it can be unit-tested in milliseconds.

```
android/
├── core/    plain Kotlin/JVM — decisions, no Android      ~2,100 lines
└── app/     Android — adapters, transport, service        ~1,550 lines
```

Read `:core` first. It is the part with the ideas in it.

---

## Part 1 — `:core`, the part that decides things

### Start here: `protocol/Envelope.kt` (146 lines)

The wire format, and the right place to begin because everything else is shaped by it.

`CommandRequest` is `{id, command, params}`. `CommandResponse` is that plus `status`, `data`,
`error` and `durationMs`. `CommandDescriptor` is how a command describes *itself* — name,
description, parameters, and a `CommandCategory` of `PRIMARY` or `DIAGNOSTIC`.

That last one is worth pausing on. The agent publishes its own catalog, so the Python client can
list and group commands it was never compiled against. When you see `system.commands` later, this is
what it returns.

Then `protocol/Errors.kt` (45) for the seven `ErrorCode` values, and `protocol/Params.kt` (134),
which is the only place a request's types are enforced — every accessor either returns a usable
value or throws `InvalidParamsException`. It is stricter than it looks: a JSON number where a string
belongs is refused rather than coerced, and an explicit `null` reads as absent.

### `command/Command.kt` (83 lines)

The interface every capability implements. Four members, and each one carries a decision:

```kotlin
interface Command {
    val descriptor: CommandDescriptor              // how it describes itself
    val exclusiveResource: DeviceResource? get() = null   // hardware it monopolises
    val timeout: Duration get() = DEFAULT_TIMEOUT
    suspend fun execute(params: Params): CommandOutcome
}
```

`CommandOutcome` at the bottom of the file is a sealed interface of `Success` and `Failure`. Note
what that buys: "this device has no rear camera" is a `Failure`, a value — not an exception. A
predictable refusal cannot be confused with a bug, and the two are handled differently one file
along.

### `command/CommandDispatcher.kt` (122 lines)

**The single most important file in the project.** Every transport funnels through it, so everything
that is not a command's own business lives here exactly once: name lookup, the exclusivity lock, the
time budget, and turning any failure into a typed response.

Read `dispatch()`, then `run()`. The split exists because there are seven ways to answer and logging
each at its own `return` would be seven chances to miss one — so `dispatch` logs, and `run`
decides. Note that `CancellationException` is deliberately rethrown rather than caught: a caller
hanging up is not an outcome, and recording one would be a lie.

Alongside it, two short files: `CommandRegistry.kt` (35) is a name→command map that rejects
duplicates at construction, and `ResourceLocks.kt` (27) is a `Mutex` per `DeviceResource`. That is
the whole concurrency story — the camera is physically exclusive, `getprop` is not, and the
dispatcher applies whichever the command declared.

### `port/` — eight interfaces, ~300 lines total

What the core needs from the outside world, and the reason it needs no Android. Read them in this
order:

| Port | What it abstracts |
|---|---|
| `Clock` | the time, so tests can hold it still |
| `PropertyReader` | `getprop` |
| `CameraController` | take one rear photograph |
| `PhotoStore` | where photos go, and how they come back out |
| `ActivityStarter` | resolve and launch another app |
| `PermissionInspector` | what is granted |
| `PermissionPrompt` | ask the human, now |
| `CommandLog` | narrate what happened |

`PhotoStore.kt` (71) is the one to read closely. Its four methods encode a real constraint: the
camera writes to a private scratch file first, and only a *completed* capture is published to shared
storage. MediaStore offers no destination that can be rolled back, so publishing first would leave a
torn file in the user's Documents whenever the sensor failed.

`PermissionPrompt` is one method — `requestAttention()` — and exists because with no setup screen,
the only moment a human is reliably looking at the phone is just after a command failed for want of
a permission. So the command asks, as part of failing.

### `logic/` — the pure decisions, ~500 lines

Everything worth testing that needs no device. This is where the project keeps its judgement:

- **`CameraAppLaunch.kt`** (103) builds the ordered chain of intents that `camera.open` tries.
- **`CameraAppChoice.kt`** (41) picks between several camera apps. Read the comment about
  `resolveActivity` returning the system chooser — that is the bug this file exists to avoid.
- **`PhotoPaths.kt`** (93) validates the destination directory. This is the whole security story for
  the one parameter that arrives over the network and becomes a folder; 17 tests.
- **`PhotoNaming.kt`** (67), **`PhotoIndex.kt`** (68), **`PropertyKeys.kt`** (41),
  **`LanAddresses.kt`** (43), **`GetPropOutput.kt`** (14).

`PhotoIndex` is a good illustration of where the boundary gets drawn. The photo *store* needs a
`ContentResolver` and can only run on a device — but the bookkeeping around it (a truncated line
from a process that died mid-write, an entry whose photo the user deleted, compaction) is where the
edge cases are, so it lives here and has ten tests.

### `command/impl/` — the six capabilities, ~570 lines

Now the commands themselves make sense. Read `CapturePhotoCommand.kt` (158) first — it is the
assignment's centrepiece and the most instructive:

1. permission check → prompt the human and fail
2. rear-sensor check → fail rather than quietly use the front camera
3. validate quality, filename, destination — **all before the shutter fires**
4. capture to scratch, publish, or discard

That ordering is the point: everything that can be rejected is settled before anything happens, so a
request that was never going to work leaves no photograph behind.

Then `OpenCameraCommand.kt` (131) for the strategy chain, `GetPropCommand.kt` (105),
`ListCameraAppsCommand.kt` (75), `StatusCommand.kt` (67), `ListCommandsCommand.kt` (43).

---

## Part 2 — `:app`, the part that touches Android

Everything here implements something you have already read. That is the payoff of reading `:core`
first: no file in this half introduces a new idea, only a platform detail.

### `di/AppContainer.kt` (150 lines) — read this first

The entire composition of the application in one readable file. Manual constructor injection, no
Hilt, no annotation processing. If you want to know what is wired to what, it is all here — and
`commands()` at the bottom is the registry, one line per capability.

### `adapter/` — one file per port, ~640 lines

Each is a thin shell over a port you have already met:

| Adapter | Notes |
|---|---|
| `CameraXController` (137) | `ImageCapture` bound with no preview. The one adapter that genuinely cannot be unit-tested — there is no rear camera on a desktop JVM, and faking CameraX would only test the fake |
| `MediaStorePhotoStore` (161) | writes to `Documents/cam-remote/` with **no storage permission** — read the header comment for why that is possible |
| `ExecGetPropReader` (67) | runs `/system/bin/getprop` with no shell |
| `SystemPropertiesReader` (28) | the reflective fallback; `FirstAvailablePropertyReader` in `:core` chains them |
| `IntentActivityStarter` (65) | `queryIntentActivities`, then launch by explicit component |
| `LogcatCommandLog` (74) | every command and outcome under one tag |
| `AndroidPermissionInspector` (48), `AndroidPermissionPrompt` (34), `LocalAddresses` (36) | |

### `service/RemoteControlService.kt` (317 lines) — the biggest file, and why

It is the app's lifecycle. It owns the HTTP server, the Wi-Fi lock, the `LifecycleOwner` CameraX
binds to, and the notification — which is the whole of the agent's interface: it reports the address
to point a client at, keeps that address current as the device's own changes, and carries the only
way to switch the agent off.

Read `onStartCommand` → `startInForeground` → `foregroundServiceTypes`. That last one is subtle and
has already caused one real bug: a foreground service's types are fixed when `startForeground` is
called, and a type not declared in the manifest is rejected outright.

`BootReceiver.kt` (34) brings it back after a reboot, if it was running before one.

### `setup/LaunchActivity.kt` (152 lines)

The only Activity, and it draws nothing — the manifest gives it a fully transparent theme, so the
only thing a user ever sees is a native Android dialog. It exists because Android will not let a
background service request a permission without *some* Activity to host the dialog.

Read `continueSetup()`. It offers whatever is still missing and finishes when nothing is, with each
step offered at most once per launch — that guard is what stops a declined permission becoming a
carousel.

### `transport/http/` (200 lines)

`CommandApi.kt` (152) has three routes — `POST /v1/command`, `GET /v1/health`,
`GET /v1/media/{id}` — and holds no policy: it decides which HTTP status expresses a failure and
nothing else. `HttpCommandServer.kt` (48) is the Ktor engine's lifetime.

---

## What to read to understand a change

| To understand… | Read |
|---|---|
| What the agent can do | `di/AppContainer.kt`, `commands()` |
| What one command does | `core/command/impl/<name>.kt` |
| Why a request failed | `core/command/CommandDispatcher.kt` |
| How the device actually does it | `app/adapter/` |
| What the wire looks like | `core/protocol/Envelope.kt` |
| When the agent runs | `app/service/RemoteControlService.kt` |

## Where the tests are

`core/src/test` is where the thinking is checked — 177 tests, no Android, about a second. Start with
`CommandDispatcherTest` and `PhotoPathsTest`; between them they cover most of what could go wrong.

`app/src/test` (10) drives the Ktor routes with `testApplication`. `app/src/androidTest` (7) covers
only what a desktop JVM structurally cannot: a real sensor producing a real JPEG, a real property
store, a real socket.

`core/src/testFixtures` holds `FakeClock` and `TestCommand`, published to `:app` so the transport
tests drive real commands rather than re-inventing doubles.

## Adding a command

Two files: the new `Command` class in `core/command/impl/`, and one line in `AppContainer.commands()`.
No route, no protocol change, no client release — `system.commands` publishes the catalog, so an
existing client lists it without being rebuilt. [EXTENDING.md](EXTENDING.md) walks through it with
real code.
