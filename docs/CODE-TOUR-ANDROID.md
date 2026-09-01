# Code tour — the Android agent

A reading order for the `android/` project, for someone opening it for the first time. About 3,600
lines of Kotlin across two modules; this walks the spine of it in the order that makes each file
make sense when you reach it.

Parts 1 and 2 walk the files. Parts 3 and 4 then follow the two flows that matter end to end — a
command arriving over the wire, and the app's first launch — so if you would rather see the code
move than be introduced to it, start at [Part 3](#part-3--the-life-of-a-command).

[ARCHITECTURE.md](ARCHITECTURE.md) is the map and this is the walk;
[PACKAGES.md](PACKAGES.md) says what each package is responsible for once you need to put
something somewhere.

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

## Part 3 — The life of a command

Parts 1 and 2 say what each file is. This says what actually happens between a request landing on
the socket and a reply leaving it. Which command it is barely matters — all six travel the same
path, and the path is the design.

Take a capture arriving over the wire:

```
POST /v1/command
{"id":"a1b2c3","command":"camera.capture","params":{"filename":"door"}}
```

### 1. The socket — `HttpCommandServer` (`transport/http/`)

Ktor's CIO engine, bound to `0.0.0.0` on the configured port, started by the service and owned by
it. Each request is handled on its own coroutine, so requests really are concurrent — which is a
problem for exactly one device on this phone, and step 5 is where that is dealt with.

### 2. The route — `CommandApi.kt`

`POST /v1/command` does three things and holds no policy:

```kotlin
val request = ProtocolJson.decodeRequest(call.receiveText())   // text stops existing here
val response = dispatcher.dispatch(request)                    // the only call into :core
call.respondJson(HttpStatusCode.OK, ProtocolJson.encodeResponse(response))
```

A body that will not parse is answered `400` with an `ErrorEnvelope` and never reaches the
dispatcher. That is the single decision this route makes on its own.

Note the status on the third line: **`200`, always**. A command that *failed* is a `200` carrying
`status=ERROR` and a typed `ErrorCode`. HTTP statuses are kept for things that never became a
command at all — `400` a malformed body, `404` an unknown endpoint or an unknown media id, `500` a
defect in the adapter itself. "Was the request understood" and "did the command succeed" are
different questions, and answering them on different channels is what lets the client have one
parser for the second.

### 3. Crossing the boundary — `dispatcher.dispatch(request)`

This one call is the entire surface between the platform and the application. Everything from here
down is plain Kotlin with no Android and no Ktor on the classpath — which is why the instrumented
tests drive real commands with no HTTP involved at all, and why those three lines above are
essentially the whole of what a second transport would have to write.

### 4. `dispatch()` — narrate, then delegate

```kotlin
log.received(request)
val response = run(request)
log.completed(request, response)
```

The request is recorded *before* it runs, so a command that hangs still leaves evidence of having
arrived:

```
cam-remote-app:CamRemote  I  --> camera.capture  id=a1b2c3  params={"filename":"door"}
```

The split exists because `run()` has seven ways to produce a response, and logging at each of its
own returns would be seven chances to forget one.

### 5. `run()` — lookup, budget, lock

Three wrappers, in this order, before the command sees anything:

```kotlin
val command = registry[request.command] ?: return /* UNKNOWN_COMMAND, listing what does exist */
withTimeout(command.timeout) {
    locks.withResource(command.exclusiveResource) { command.execute(request.params) }
}
```

Both the budget and the exclusivity are *declared by the command itself* — `CapturePhotoCommand`
asks for 45 seconds and `DeviceResource.CAMERA`; `GetPropCommand` asks for ten and declares no
exclusive resource at all, so it skips the mutex and runs genuinely in parallel with anything else.

**The nesting order is the point.** The timeout is *outside* the lock, so a second capture arriving
while the first is still metering waits on the mutex and that wait is charged against its own
budget. It gets a `TIMEOUT` naming the busy resource, rather than blocking invisibly until the
client's socket gives up.

### 6. The command — `CapturePhotoCommand.execute`

The only step that differs between commands, and even here the shape is common: check what can be
refused, do the work, describe the result.

Everything it touches is a port — `permissions`, `camera`, `photos`, `clock`, `permissionPrompt`.
Five interfaces, all handed to its constructor by `AppContainer` at wiring time. It does not know
that `photos` is MediaStore or that `camera` is CameraX, which is exactly why the file holding all
the decisions holds none of the device.

It returns a **value**: `CommandOutcome.Success(json)` or `CommandOutcome.Failure(error)`. "This
device has no rear camera" is a `Failure`, not an exception, so a predictable refusal can never be
confused with a bug.

### 7. Back in `run()` — every way this can end

| What happened | Becomes |
|---|---|
| `CommandOutcome.Success` | `status=OK`, the payload, `durationMs` |
| `CommandOutcome.Failure` | `status=ERROR` with the command's own code and remediation |
| the name was not in the registry | `UNKNOWN_COMMAND`, listing the names that are |
| `withTimeout` expired | `TIMEOUT`, naming the busy resource if the command declared one |
| `InvalidParamsException` from a `Params` accessor | `INVALID_PARAMS` |
| any other exception | `INTERNAL` |
| `CancellationException` | **rethrown** — deliberately never an outcome |

The last two rows are the interesting pair. An unforeseen exception is caught so that a bug in one
command cannot take the agent down for the other five. Cancellation is deliberately *not* caught:
the client hung up or the service is shutting down, and swallowing it would break structured
concurrency and leave a coroutine running in a scope that has already died.

`TimeoutCancellationException` is a subclass of `CancellationException`, so its `catch` has to stay
above the one that rethrows. The file says so in a comment, because swapping those two blocks would
silently turn every timeout into a dropped connection.

### 8. Out again

`log.completed` writes the second line, at a level chosen by the outcome — `INFO` it worked, `WARN`
the device said no, `ERROR` a defect in the agent:

```
cam-remote-app:CamRemote  I  <-- camera.capture  OK  in 2374ms  {"id":"kZ8…","path":"Documents/cam-remote/door.jpg",…}
```

Then the route serialises and writes. Every command in the log is two lines, always in that order,
which is what makes `adb logcat -s cam-remote-app:CamRemote` the whole story of what the device
did. Every tag this app writes carries the same `cam-remote-app` prefix — `adb logcat | grep
cam-remote-app` is the version that also catches the service, the boot receiver and the permission
prompt, with none of the framework/CameraX noise that shares the app's PID.

### 9. The second round trip

A capture's payload carries `downloadPath: "/v1/media/kZ8…"`, and the client follows it — a path on
the handset is of no use to someone not holding the handset. `GET /v1/media/{id}` calls
`photos.open(id)` and streams the bytes with a `Content-Disposition` filename, streamed rather than
buffered because a full-resolution JPEG is several megabytes and the agent shares a heap with
everything else the phone is doing.

### What never happens on this path

No authentication — one agent, one client, a trusted LAN, argued in [DESIGN.md](DESIGN.md) §7. No
object graph built per request: the dispatcher, the registry and every adapter were constructed once
when the service started. No reflection, and no classpath scanning — the registry is a list.

---

## Part 4 — The first launch

The other flow worth following end to end, because it is where all the Android-specific pain lives:
what happens between tapping the icon and the agent answering on a port.

### 1. The icon → `LaunchActivity`

The manifest's only `LAUNCHER` entry, with `Theme.Translucent.NoTitleBar` and
`excludeFromRecents`. It draws nothing at all — the only thing a user ever sees is a native Android
dialog on top of whatever was already on screen.

It exists because Android offers no other route to those dialogs: a runtime permission can only be
requested from an activity, and a background service cannot ask for one. Opening the app once is the
single unavoidable manual step, and no Android app can avoid it.

### 2. `onCreate` — three lines that matter

```kotlin
container.config.isEnabled = true      // "the agent is meant to be running"
RemoteControlService.start(this)       // startForegroundService
continueSetup()                        // begin asking
```

`isEnabled` is the closest thing to an on/off switch this app has. There is no screen to put a
toggle on, so *opening the app* is the gesture that means "run", and `BootReceiver` reads that flag
later to decide whether to come back after a reboot.

Note the ordering: **the service starts before any permission is asked for.** That is deliberate —
the port opens immediately, so `status` and `getprop` answer while the user is still working through
dialogs, and `status` is what names whatever they declined.

### 3. `RemoteControlService.onCreate`

Builds the container — `AppContainer.from(applicationContext)`, double-checked, the same instance
the activity is holding — and registers the notification channel at `IMPORTANCE_LOW` with no badge.
An ongoing status notification should be present, not attention-seeking.

### 4. `onStartCommand` → `startInForeground()`

`buildNotification()` first. It reads `LocalAddresses.firstLanIpv4()`, remembers it in
`shownAddress`, and produces the agent's entire user interface:

- **the text** — `Accepting commands on 10.0.0.4:8099`, the only place an operator can learn where
  to point `--host`
- **the tap target** — a `PendingIntent` back to `LaunchActivity`. A notification tap may always
  start an activity, even from the background, which a service's own `startActivity` may not. This
  is the guaranteed route to a permission prompt.
- **the "Terminate service" action** — a `PendingIntent.getService` carrying `ACTION_STOP` back to
  this same service, on a distinct request code so the two `PendingIntent`s do not collide. With no
  screen, this is the only way to switch the agent off.

Then `foregroundServiceTypes()`, which is subtler than it looks and has already caused one real
crash and one real bug:

```kotlin
var types = if (SDK_INT >= 34) FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
if (container.permissions.status().camera) types = types or FOREGROUND_SERVICE_TYPE_CAMERA
```

A type not declared in the `<service>` element throws `IllegalArgumentException` outright — an
earlier version passed `dataSync`, which the manifest does not declare, and the agent died at
startup on every device below API 34. And from API 34 a *camera*-typed service may not be started at
all without the camera permission already granted. So the type is claimed only once it is true, and
`types == 0` calls the two-argument `startForeground` rather than passing a zero.

The consequence for a first launch: at this moment the camera permission does not exist yet, so the
service starts **without** the camera type. Step 7 is what closes that window.

### 5. `startServer()` — the port opens

```kotlin
val dispatcher = container.dispatcherFor(this)
server = HttpCommandServer(port) { commandApi(dispatcher, container.photos, container.deviceDescription()) }
    .also { it.start() }
acquireWifiLock()
watchForAddressChanges()
```

`this` is the `LifecycleService`, and it is passed as the `LifecycleOwner` CameraX binds its use
cases to — the reason this is a `LifecycleService` rather than a plain `Service`, in an app with no
lifecycle anywhere else. `dispatcherFor` builds the registry (with a `lateinit` back-reference, so
`system.commands` can report the registry it is itself a member of) and wraps it in the dispatcher
with the shared `LogcatCommandLog`.

The port comes from `ServerConfig` — SharedPreferences, default 8099.

The `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`) is the price of making the network the only transport:
without it the radio powers down with the screen and inbound connections are simply dropped. The
battery-optimisation exemption asked for in step 6 covers the other half of the same problem.

### 6. Back on the activity — `continueSetup()`

One function, re-entered from every dialog's callback, offering whatever is still missing:

| Step | How it is asked | Why |
|---|---|---|
| `CAMERA`, `POST_NOTIFICATIONS` | one `RequestMultiplePermissions` launch | the only two runtime permissions; notifications only counts from API 33 |
| **Appear on top** | `ACTION_MANAGE_OVERLAY_PERMISSION` | lets a background app start the camera app at all, and is the documented exemption for starting a camera FGS from the background |
| **Battery optimisation** | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | the agent stops answering with the screen off otherwise |

Three details do the work:

**State is re-read, never assumed.** Both Settings screens report `RESULT_CANCELED` however the user
leaves them, so the result code carries nothing; what actually changed is read back from the system.

**`offered.add(step)` returning `false` is the loop guard.** A step declined stays declined for this
launch, so the flow moves on instead of reopening the same Settings screen forever. Anything left is
picked up the next time a command finds it missing. The set survives a rotation through
`onSaveInstanceState`.

**Overlay is offered before the battery exemption**, because it is what lets a *later* run of this
very activity be started from the background — the one worth having if the user grants only one.

### 7. The last two lines — and the bug they fix

```kotlin
RemoteControlService.start(this)   // again
finish()
```

Starting the service a second time looks redundant and is not. A foreground service's types are
fixed at the moment `startForeground` is called, and it was called in step 4 — *before* the camera
dialog. Granting the camera permission a moment later does not retrofit the `camera` type onto a
service already running without it, and from API 34 a service lacking that type may not touch the
sensor.

The symptom was a first `take-picture` timing out after 45 seconds on a device the user had just
finished setting up, with every permission showing as granted. `onStartCommand` re-asserts the
types, so one extra intent closes the window. Then the activity finishes and the app has no visible
component again.

### 8. Steady state

The notification is the whole interface, and it keeps itself honest. A
`registerDefaultNetworkCallback` fires on `onLinkPropertiesChanged` / `onAvailable` / `onLost`;
`refreshNotification()` compares against `shownAddress` and reposts **only when the address actually
changed** — network callbacks fire freely enough that reposting on each one would be churn the user
can see. DHCP moved the development handset three times in one afternoon, which is how this feature
came to exist.

### 9. Stopping, and coming back

**Terminate service** sends `ACTION_STOP` to `onStartCommand`, which calls `shutDown()`: clear
`config.isEnabled`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`, and return
`START_NOT_STICKY` so the system does not hand back an agent the operator just switched off.
`onDestroy` releases the socket, the network callback and the Wi-Fi lock. Clearing the flag matters
as much as stopping: without it `BootReceiver` would bring the agent back the next morning and
"Terminate service" would read as a lie.

Opening the app sets the flag again — that is the way back.

**After a reboot**, `BootReceiver` checks that same flag and restarts the service, so a fresh
install that has never been opened stays quiet.

**When a command finds a permission missing**, it calls `PermissionPrompt.requestAttention()` as part
of failing, and `AndroidPermissionPrompt` tries to bring `LaunchActivity` up with
`FLAG_ACTIVITY_NEW_TASK`. Best-effort — Android does not reliably let a background process pop an
activity — and its failure is swallowed, because a command whose permission check already failed
must return *that* error rather than a new one about the prompt. The notification's tap target is
the guaranteed fallback.

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
