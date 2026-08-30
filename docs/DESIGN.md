# Design decisions

The assignment asks for three small capabilities and says twice what it is actually looking for:
modular design, coding standards, and the ability to add new functionality with minimal change to
what already exists. It also says explicitly that no GUI is required. This document records the
decisions that follow from that, including the ones that were rejected and why.

---

## 1. What kind of application this is

**Decision: a request/response agent, architected as ports and adapters.**

The assignment says no GUI is required. That single sentence rules out most of what Android
architecture guidance assumes. MVVM, MVC and MVI are *presentation* patterns: their job is mediating
between a view and mutable view state. With no view there is no view state, and adopting them here
would produce ViewModels that nothing observes — architecture as costume.

What this actually is, is a small server that happens to run on a phone. A message arrives, it is
dispatched, a device capability is exercised, a reply goes back. So the structure is the one that
fits that shape:

- **`:core`** holds the application logic — the protocol, the commands, the decision-making — and
  depends on nothing from Android.
- **Ports** are the interfaces the core needs: `CameraController`, `PropertyReader`, `PhotoStore`,
  `ActivityStarter`, `PermissionInspector`, `PermissionPrompt`, `Clock`.
- **Adapters** in `:app` implement those with CameraX, `ProcessBuilder`, MediaStore, `Intent`,
  and so on.
- The **driving port** is `CommandDispatcher`, the single entrance every transport goes through.

Dependencies point inward only, and the two-module split makes that a fact of the build graph rather
than a promise: `:core` has no Android dependency on its classpath, so a careless import fails to
compile rather than quietly eroding the boundary.

**What replaces the ViewModel.** Strip MVVM down and a ViewModel provides two things — surviving
configuration changes, and owning a coroutine scope. Here `RemoteControlService` does both, and being
a `LifecycleService` it also supplies the `LifecycleOwner` that CameraX requires. `AppContainer` owns
the singletons. The one activity the app has, `LaunchActivity`, draws nothing of its own — it exists
solely to host native Android dialogs — so it has no state worth a ViewModel either.

**Rejected: full Clean Architecture.** Use cases, repositories, mappers, and domain/data/presentation
layering would be a great deal of structure for six commands, and a reviewer would be right to read
it as cargo cult. Ports and adapters plus the Command pattern is the right *amount*. Knowing where to
stop is part of the answer.

---

## 2. How the device is controlled

**Decision: an HTTP + JSON server inside the app, reached over Wi-Fi. No adb anywhere in the
product.**

The assignment does not specify a transport, which makes this the largest decision in the project.

*Considered and rejected:*

- **`adb shell am broadcast` into a `BroadcastReceiver`.** Simplest to build, and the usual answer.
  But it needs a USB cable and a developer machine, which makes "remotely" a stretch, and it puts a
  developer tool in the product's runtime path.
- **A cloud relay or MQTT broker.** Genuinely remote, over the internet. But it needs an account or a
  server, and a reviewer cannot run the submission without credentials that are not theirs.
- **Firebase Cloud Messaging.** The most production-realistic answer — it is what a real
  device-management product would use — and disqualified for the same reason, plus a `google-services.json`
  that ties the code to one project.

*Chosen:* the app runs a Ktor server on the local network; the Python client talks to it over TCP.
A reviewer needs nothing but the two devices and the same Wi-Fi. And because the transport is plain
TCP, controlling the phone from outside the LAN needs no code at all — an overlay network such as
Tailscale gives the handset a stable address reachable from anywhere.

**The consequence: adb had to go entirely.** Once the transport is the network, `adb` is only a
convenience, and keeping it would have hidden real problems. It turned out to be *necessary* as well
as principled — the ColorOS handset this was developed against refuses `pm grant` and `appops set`
from adb outright, so the shortcut would not have worked anyway. The agent therefore has to say
where it is by some means the operator can read, which is why its notification shows `ip:port`.

---

## 3. The three Android constraints that shaped the agent

These are the parts of this assignment that are genuinely hard, and none of them are visible from the
requirements.

**Background activity launch is blocked.** Since Android 10, an app in the background may not start
an activity — and this agent is, by design, always in the background. The documented escape is the
`SYSTEM_ALERT_WINDOW` ("Display over other apps") permission. So `camera.open` checks
`Settings.canDrawOverlays()` first and, when it is missing, returns `PRECONDITION_FAILED` with the
fix attached, rather than firing an intent the system silently discards. Verified working on API 34:
the agent opens ColorOS's camera app from a background service.

**A camera-typed foreground service cannot be started from the background** (Android 14+). The same
overlay grant is one of the listed exemptions, so one grant unlocks both problems. There is a second
subtlety: from API 34 you may not even *declare* the camera service type without holding the camera
permission — asking for it regardless throws and takes the agent down. So `RemoteControlService`
computes its service types from the permissions actually held, and starts without the camera type on
a half-configured device, still serving `device.getprop` and `system.status` so the operator can see
what is missing.

**Runtime permissions need an activity.** There is no remote route to them, and on this handset no
adb route either. Hence `LaunchActivity` — the app's only screen, and specifically not the GUI the
assignment says is unnecessary, which means a *control* GUI. It draws nothing of its own: it is a
fully transparent trampoline whose entire job is requesting whatever the device is still missing, and
it runs either because the user tapped the app icon (unavoidable — Android has no other way to start
an app for the first time) or because a command that needed a permission called
[`PermissionPrompt.requestAttention`](#4-the-command-layer) as part of failing. Section 7 covers this
in full.

A fourth constraint appeared once Wi-Fi became the only transport: **Doze and Wi-Fi power saving**
drop inbound connections with the screen off. The service holds a `WifiLock`, and
`LaunchActivity` requests a battery-optimisation exemption alongside the others. Aggressive OEM
process killers are beyond what code can fix, and the README says so rather than pretending
otherwise.

---

## 4. The command layer

**Decision: one `Command` implementation per capability, in an explicit registry.**

```kotlin
interface Command {
    val descriptor: CommandDescriptor
    val exclusiveResource: DeviceResource? get() = null
    val timeout: Duration get() = DEFAULT_TIMEOUT
    suspend fun execute(params: Params): CommandOutcome
}
```

Adding a capability means writing one class and adding one line to `AppContainer.commands`. Nothing
else changes — not the transport, not the protocol, not the client. That is the extensibility claim,
and it is small enough to check.

**Registration is a hand-written list**, not classpath scanning or annotation processing. It costs a
line per command and buys two things worth more here: the complete set of capabilities is readable in
one place, and R8 cannot strip a command that nothing appears to reference.

**Commands return their failures rather than throwing them.** "This device has no rear camera" is an
expected outcome, not an exception, and encoding it as one would make it indistinguishable from a
bug. Parameter problems are the exception — they *are* thrown, from `Params`, so that validation is
written once and every command reports a bad parameter identically.

**`system.commands` makes the agent self-describing.** The catalog, including each parameter, is
served from the device, so a control application can list capabilities it was never compiled against.

**A command that hits a missing permission asks for it, right then.** `CapturePhotoCommand` and
`OpenCameraCommand` both take a `PermissionPrompt` and call `requestAttention()` as part of returning
their failure — see section 7 for why this exists and how it is triggered.

---

## 5. Concurrency

Ports and adapters says nothing about this, and it matters: an HTTP server is concurrent by nature
and a camera is physically exclusive.

A command declares `exclusiveResource`, and the dispatcher holds the corresponding mutex around it.
The camera is exclusive; a property read is not, so a slow capture never blocks a trivial `getprop`.
The wait happens *inside* the command's timeout, so a client queued behind a busy camera gets a
prompt `TIMEOUT` instead of hanging until the transport gives up. Timeouts are per-command — 45
seconds for a capture, 10 for a property read — because one budget cannot suit both.

Cancellation propagates: when a client disconnects, the coroutine is cancelled, and the dispatcher
re-throws `CancellationException` rather than swallowing it into an error response.

---

## 6. The wire format

A single envelope in both directions, and one rule about status codes:

```json
{"id": "…", "command": "camera.capture", "params": {…}}
{"id": "…", "command": "camera.capture", "status": "OK", "data": {…}, "durationMs": 1451}
```

**Transport failures use HTTP status; command failures return 200 with `status: "ERROR"`.** A command
that fails is a *successful* HTTP exchange — the request arrived, was understood, and was answered.
Conflating the two would make every client guess whether to retry the request or the command. Failures
of both kinds carry the same `error` object, so the client needs one parser for all of them.

Errors carry a **`remediation`** field. `PRECONDITION_FAILED` alone is a dead end for whoever is
holding the phone; "grant Display over other apps" is an answer. The CLI prints it on the line below
the error.

**kotlinx.serialization, not `org.json`.** The Android SDK ships `org.json` as unimplemented stubs,
so any code touching it cannot be unit-tested on a desktop JVM — which is precisely what this
project's test strategy depends on.

**Unknown fields are ignored on read.** An agent installed on a handset cannot be upgraded in step
with the control application, so a newer client adding a field must not break an older agent.

---

## 7. Security

### There is no authentication

**Decision: `/v1/command` and every other route are open to anyone who can reach the port. No bearer
token, no pairing code, no handshake of any kind.**

This is a deliberate trade, made explicitly rather than discovered by omission, on the assumption the
project was given: exactly one agent and one client share the LAN. There is no third party to keep
out and no second client to distinguish from the real one, so a credential would be protecting
against a threat this deployment does not have.

What that costs, plainly: anyone else who joins the same Wi-Fi and finds the port can invoke every
command this agent exposes, including taking a photograph and reading device properties, with no
challenge at all. That is a real exposure on a network shared with people you do not trust, and it is
the reason `LaunchActivity` never runs the server on install — the agent only starts once someone has
opened the app, which is the one point at which the assumption ("this network, this one client") gets
implicitly re-confirmed by a human.

An earlier version of this project *did* carry a token — four random characters, generated on first
run, handed to the control machine through a tap-to-pair handshake with a sixty-second single-use
window. It is preserved at the `v1` git tag for reference. Restoring something like it, if the
single-agent-single-client assumption ever stops holding, is a bounded piece of work: reintroduce an
`AccessControl` port checked in `CommandApi`'s routes, and a way to hand the credential to the client
that does not require adb — the removed `PairingWindow` design is a reasonable starting point.

### Why the agent cannot grant itself camera access

The assignment asks the app to "handle permissions for camera access securely", which invites an
obvious question: can it be done without the user touching the phone at all?

**For an ordinary installed app, no — and that is the security model working.** `CAMERA` is a
dangerous runtime permission, and Android is built precisely so that an app cannot grant itself one.
An app that could would make the entire runtime-permission system decorative. There is no API, no
manifest flag and no legitimate trick that changes this; anything claiming otherwise is either
describing a privileged app or a bug.

So "securely" is read here as *handled properly rather than bypassed*, which means four concrete
behaviours:

- **Checked before use, never assumed.** `CapturePhotoCommand` verifies the grant and returns
  `PERMISSION_DENIED` with a remediation rather than letting CameraX throw an opaque exception.
- **Preconditions distinguished from failures.** `camera.open` returns `PRECONDITION_FAILED` naming
  the overlay permission, because Android *silently discards* a background activity launch without
  it — and the worst possible outcome is reporting success while nothing happened.
- **Diagnosable remotely.** `system.status` names exactly which grants are missing, so nobody has to
  be holding the handset to find out why a command failed.
- **Degrading rather than dying.** `RemoteControlService.foregroundServiceTypes()` declares the
  `camera` foreground-service type only once the permission exists: on API 34, declaring it without
  the grant throws and takes the whole agent down. Half-configured, the agent still runs and still
  serves `device.getprop` and `system.status`.

**And now a fifth: asked for as part of the command that needed it.** With no setup screen to send
the operator to, `CapturePhotoCommand`/`OpenCameraCommand` call `PermissionPrompt.requestAttention()`
the moment they find a permission missing — before returning their (unchanged) failure. That tries to
bring `LaunchActivity` to the foreground directly, which Android may or may not allow depending on OS
version and how recently the app was used; the guaranteed fallback is the agent's own persistent
notification, whose tap target is the same activity. Either way, the human standing at the phone —
"a user attending to this on the Android side" — sees the actual system dialog moments after the
command that needed it failed, taps Allow, and the *next* attempt succeeds. There is no separate
setup ritual; the prompt is a direct consequence of the command that required it.

Zero-touch *is* achievable, but only by holding privilege this app deliberately does not have:

| Route | What it gives | What it costs |
|---|---|---|
| **Device Owner** | `DevicePolicyManager.setPermissionGrantState` silently grants `CAMERA` and `POST_NOTIFICATIONS` to itself. How MDM products and device farms do it. | Provisioning needs a device with **no accounts configured**, so in practice a factory-reset handset; and `SYSTEM_ALERT_WINDOW` is an *appop*, not a runtime permission, so it is not covered. Removing a device owner needs `dpm remove-active-admin` or a factory reset. |
| **Platform-signed system app** | Everything pre-granted at first boot via a `default-permissions` XML, and privileged apps are not subject to the same background-launch rule — even `LaunchActivity` becomes unnecessary. | Needs an AOSP tree or the platform signing key. |
| **`adb shell pm grant`** | One command per permission. | It is adb, which this project excludes by design — and it is **refused outright by ColorOS**, as the realme handset demonstrated. Not dependable across a fleet. |

The middle row is the version that would drop the app's last screen entirely, and it is worth saying
plainly that it is unreachable from an APK someone sideloads. `LaunchActivity` is not a shortcut
around the permission model; it is what the permission model requires, reduced to the minimum Android
allows.

### The rest

- **The capture destination is confined to `Documents`**, and there is deliberately no way to name a
  different primary directory. Absolute paths, `..`, empty or space-padded directory names, an
  unrestricted character set and unbounded depth are each refused by name. See
  [§8](#8-storage); the rule is one pure function with seventeen tests.
- **Property names are validated** against `^[A-Za-z0-9][A-Za-z0-9._-]*$` even though `getprop` is
  executed with the key as a discrete argument and no shell is involved. It costs one regex and
  turns a class of question into a non-question.

What is deliberately *not* done: TLS. A self-signed certificate on a LAN service buys warnings rather
than security, and doing it properly needs a trust story this assignment does not call for. The
README states the exposure plainly instead.

---

## 8. Storage

Captures go to **`Documents/cam-remote/`** in the user's own shared storage, written through
MediaStore, and **no storage permission is declared, requested or held**.

Those two facts are the same fact. Under scoped storage an app may create files it owns anywhere in
shared storage without any storage permission at all; the permission is only needed to read or
modify files *somebody else* created. The agent only ever creates its own, so it needs nothing.
`minSdk` is **29** precisely so this holds unconditionally — on API 26–28 the same write would have
required `WRITE_EXTERNAL_STORAGE`, and rather than carry a runtime-permission path for a shrinking
tail of devices, the app declines to run there. `WRITE_EXTERNAL_STORAGE` is additionally a **no-op**
from API 30, so declaring it would have bought nothing and implied a great deal.

The assignment asks to "handle permissions for camera access and storage access securely". The
strongest form of that answer is not to request storage access carefully — it is to need none:
`CAMERA` is the only permission `camera.capture` requires, and it is requested on demand at the
moment a command first needs it (§7).

**`Documents/` rather than `Pictures/`.** These are files an operator deliberately asked a remote
agent to produce and will go looking for, not snapshots belonging in a camera roll. It also forces
the `MediaStore.Files` collection: `MediaStore.Images` only accepts a `RELATIVE_PATH` under `DCIM/`
or `Pictures/` and throws for anything else. A JPEG in `Documents/` is still indexed by MIME type,
so a gallery app may show it anyway — that is the platform's choice, not something worth fighting.

**Captures are written twice, on purpose.** The camera writes to a private scratch file in the cache
directory, and only a *completed* capture is copied into `Documents`. Publishing directly would
leave a torn file visible in the user's file manager whenever the sensor failed, and MediaStore
offers no destination that can be rolled back once CameraX has started writing to it. The copy costs
a few milliseconds, the scratch file is deleted on every path out, and anything a crash strands is
cleared at the next start.

**The destination parameter is the whole attack surface**, since a directory name arrives over the
network and becomes a folder. `PhotoPaths` confines it: always relative to `Documents`, never
absolute, no `..`, no empty or padded segments, a restricted character set, and bounded depth. It is
a pure function in `:core` with seventeen tests. MediaStore would refuse an escape of its own accord,
but relying on that would leave the agent's contract undefined and its error messages down to
whatever the platform happened to throw.

The photo index is **persisted** as JSON lines rather than kept in memory, because a foreground
service is long-lived but not immortal: a download URL handed to the control machine should keep
working after Android restarts the process. It lives in `PhotoIndex` in `:core` — deliberately not
inside the store, because once photos are addressed by content URI the store needs a
`ContentResolver` and can only run on a device, while the bookkeeping around it is where the edge
cases are. Photos in shared storage now **outlive the app** and the user may delete one from a file
manager at any time, so an id is dropped on load if the row it names has gone, and the file is
compacted so it cannot grow without bound.

One consequence worth stating plainly: because these files are the user's, they **survive
uninstall** — and the agent's claim on them does not. After a reinstall the old captures are still
in `Documents/cam-remote/`, but MediaStore no longer attributes them to this app, so their download
ids are gone and the agent cannot read them back.

---

## 9. Wiring

**Manual constructor injection through `AppContainer`.** Around fifteen objects, all singletons, no
scopes beyond the service's. Hilt would add annotation processing and indirection in exchange for
saving a file that is worth reading: as it stands, the whole composition of the application is one
page, and the command list is the complete answer to "what can this thing do?".

One thing this got wrong first, and it is worth recording because it is the sort of bug the rest of
the strategy cannot catch. An earlier version of the app (when it still had a setup screen) let that
screen and the service each construct their own container. It compiled, it unit-tested clean, and it
failed on the handset: a pairing window opened on the screen's copy while the HTTP server consulted
its own and never saw it. The fix was not to be careful — it was to make the constructor private and
hand out a single instance, so the shape that caused it no longer exists, regardless of how many
components go on to reach for the container.

---

## 10. Testing

**Test-driven throughout, red then green, one slice at a time.** The git history shows it: each
capability is a `test:` commit followed by a `feat:` commit.

TDD only works on code that runs in milliseconds without a device — which is exactly what the
hexagon delivers. All the decision-making lives in `:core` with no Android imports: target
resolution, precondition checks, path validation, key sanitising, error mapping, filename
generation, address ranking. Every port has a fake. That is 177 tests in `:core` that run in about a
second, plus 10 in `:app` for the transport routes, and 57 for the Python client.

The adapters left over are three-to-ten-line pass-throughs with no branching. `CameraAppLaunch` is
the clearest case: a pure function produces a `LaunchSpec` — tested against the no-camera-app and
missing-permission cases — and a trivial shell turns it into an `Intent`. `LaunchActivity` is the one
genuinely untestable piece by this method: it is Android-framework glue by nature (requesting
permissions, launching Settings intents, no decisions of its own to unit-test), verified instead on a
real handset alongside the camera work below.

**Three things this structurally cannot cover**, named rather than papered over:

1. that a real sensor produces a real JPEG,
2. that the OS permits the background activity launch on a given Android version,
3. that the agent is reachable across a physical network at all.

Those are the seven instrumented tests and the manual run, and both were done on real handsets — a
Realme RMX3563 and a Samsung Galaxy S24, both Android 14. The instrumented capture test asserts the
JPEG magic bytes, because "a file exists" is not the same claim as "a photograph was taken".

Two things learned from running them on a real OEM device are baked into the suite.
`GrantPermissionRule` is not portable — ColorOS refuses runtime grants from UiAutomation as well as
from the shell — so the suite attempts the grant and skips the camera test with an explanation
rather than failing every test in the class. And an app may not make cleartext HTTP requests since
API 28, so the test that connects to its own server speaks HTTP over a bare socket rather than
loosening the shipped app's network policy to suit a test.

---

## 11. The control application

**Standard library only.** `argparse`, `urllib`, `socket`, `tomllib`. A reviewer can run it on a
clean machine with no virtualenv and no network install between them and the demo. The tests use
`unittest` for the same reason.

It **mirrors the agent's layering** — transport, client, one module per command, a registry — so
adding a verb is one file and one line on either side, and the symmetry is visible when the two are
read together.

**Exit codes are part of the contract**: 0 success, 1 the agent reported a failure, 2 usage, 3 the
agent could not be reached. A script needs to tell "the phone said no" from "the phone was not
there".

**The agent's address is a required argument**, and that is the second design decision this client
has reversed on evidence. It once browsed for `_camremote._tcp` over mDNS and had a `pair` verb that
saved what it found. Both are gone. The browser worked — its parser handled real compression
pointers and a SRV record resolved by a separate A record — but the handsets did not: the Galaxy S24
announces its record on registration and then answers no query at all, so discovery succeeded for a
few seconds after the app started and never afterwards. `docs/DEVICES.md` records the measurements.

Keeping it would have meant a client that usually cannot find a device and a `pair` verb whose saved
address goes stale the next time DHCP moves the phone. An address that is sometimes found is worse
than one that is always typed — particularly when the agent already displays its own `ip:port` in a
notification, which is four numbers read off a screen the operator is holding anyway. Roughly four
hundred lines went with the decision, including the whole mDNS implementation and the config file
that existed to remember its results.

The agent's own advertisement went with it. Keeping a service registration nothing consumed would
have left `NsdServiceAdvertiser`, a multicast lock and two Wi-Fi permissions in the app to support a
feature that had been deleted — the agent now opens a port, serves commands on it, and names itself
in a notification. Anyone wanting the advertisement back has it in the history, and it was thirty
lines against `NsdManager`.

---

## 12. Known limitations

- **The API is unauthenticated.** See section 7 — a deliberate trade for the assignment's stated
  single-agent-single-client scope, not an oversight.
- **A busy camera costs a full timeout.** If another app holds the sensor, CameraX blocks rather than
  failing fast, so `take-picture` returns `TIMEOUT` after 45 seconds. Correct, but slow; there is no
  cheap "is the camera free?" check to pre-empt it with.
- **The lens hint on `camera.open` is best-effort.** No platform contract obliges a camera app to
  honour it. The rear-camera *requirement* is met by `camera.capture`, which drives the sensor
  directly and depends on nobody's goodwill.
- **`requestAttention()`'s direct activity launch is best-effort.** Whether it succeeds depends on
  Android version and recent app usage; the persistent notification is the mechanism guaranteed to
  work, at the cost of needing the operator to notice and tap it.
- **OEM process killers** can stop the agent regardless of Android's own rules. Opening the app
  restarts it, and it restarts after a reboot, but a vendor protected-apps list may still be needed.
- **No TLS**, as discussed above.
- **One agent per control machine** in the config file. `--host` covers the rest; a named-profiles
  file would be the natural next step.
