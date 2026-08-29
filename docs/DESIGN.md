# Design decisions

The assignment asks for three small capabilities and says twice what it is actually looking for:
modular design, coding standards, and the ability to add new functionality with minimal change to
what already exists. This document records the decisions that follow from that, including the ones
that were rejected and why.

---

## 1. What kind of application this is

**Decision: a request/response agent, architected as ports and adapters.**

The assignment asks for an app with no GUI. That single sentence rules out most of what Android
architecture guidance assumes. MVVM, MVC and MVI are *presentation* patterns: their job is mediating
between a view and mutable view state. With no view there is no view state, and adopting them here
would produce ViewModels that nothing observes — architecture as costume.

What this actually is, is a small server that happens to run on a phone. A message arrives, it is
dispatched, a device capability is exercised, a reply goes back. So the structure is the one that
fits that shape:

- **`:core`** holds the application logic — the protocol, the commands, the decision-making — and
  depends on nothing from Android.
- **Ports** are the interfaces the core needs: `CameraController`, `PropertyReader`, `PhotoStore`,
  `ActivityStarter`, `PermissionInspector`, `Clock`.
- **Adapters** in `:app` implement those with CameraX, `ProcessBuilder`, the filesystem, `Intent`,
  and so on.
- The **driving port** is `CommandDispatcher`, the single entrance every transport goes through.

Dependencies point inward only, and the two-module split makes that a fact of the build graph rather
than a promise: `:core` has no Android dependency on its classpath, so a careless import fails to
compile rather than quietly eroding the boundary.

**What replaces the ViewModel.** Strip MVVM down and a ViewModel provides two things — surviving
configuration changes, and owning a coroutine scope. Here `RemoteControlService` does both, and being
a `LifecycleService` it also supplies the `LifecycleOwner` that CameraX requires. `AppContainer` owns
the singletons. The one screen that does exist, `SetupActivity`, is simple enough to read its state
directly; a ViewModel there would be ceremony.

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
convenience, and keeping it would have hidden real problems. Removing it forced three things that
improved the result: a proper setup screen instead of `pm grant`; mDNS discovery instead of asking
the operator for an IP address; and a pairing handshake instead of reading a token out of a shell.
It also turned out to be *necessary* — the ColorOS handset this was developed against refuses
`pm grant` and `appops set` from adb outright, so the shortcut would not have worked anyway.

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
overlay grant is one of the listed exemptions, so one setup step unlocks both problems. There is a
second subtlety: from API 34 you may not even *declare* the camera service type without holding the
camera permission — asking for it regardless throws and takes the agent down. So
`RemoteControlService` computes its service types from the permissions actually held, and starts
without the camera type on a half-configured device, still serving `device.getprop` and
`system.status` so the operator can see what is missing.

**Runtime permissions need an activity.** There is no remote route to them, and on this handset no
adb route either. Hence `SetupActivity` — the app's only screen, and not the GUI the assignment
forbids, which means a *control* GUI. It grants permissions, switches the agent on, shows the
address, and opens the pairing window. Nothing on it takes a photograph.

A fourth constraint appeared once Wi-Fi became the only transport: **Doze and Wi-Fi power saving**
drop inbound connections with the screen off. The service holds a `WifiLock` and setup requests a
battery-optimisation exemption. Aggressive OEM process killers are beyond what code can fix, and the
README says so rather than pretending otherwise.

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

The agent listens on the local network, so the threat model is "somebody else on this Wi-Fi".

- **A bearer token on every request**, generated on first run — not baked into the build, so two
  installs of the same APK do not share a secret. The comparison is constant-time and length-safe.
- **The token is four characters, deliberately.** This is a proof of concept, and a token that short
  can be read off the phone's screen and typed in a second, which makes the manual fallback to
  pairing genuinely usable. The cost is not hidden: four characters from a 32-symbol alphabet is
  about a million possibilities, which anyone on the same network can exhaust in well under a minute.
  It keeps a neighbour from stumbling onto the port; it does not keep out anyone who wants in. The
  alphabet omits `O`/`0` and `I`/`1` so it survives being copied by eye. Restoring a serious secret is
  one constant — `Tokens.LENGTH` — because nothing else in the project depends on the length.
- **Pairing is a deliberate act.** With no adb there has to be some in-band way to hand over the
  token, so the user taps **Pair**, which opens a sixty-second window that closes after one claim.
  Someone on the same network can only pair if they are also holding the phone at that moment.
- **The server only runs while the user has switched it on.** An app that starts listening on the
  local network the moment it is installed would be a poor citizen whatever its purpose.
- **The capture destination is confined to an allow-list**, canonicalised before checking — a textual
  prefix comparison would be fooled by `..`, by a symlink, and by a sibling directory whose name
  merely starts with the root's. All three are in the tests.
- **Property names are validated** against `^[A-Za-z0-9][A-Za-z0-9._-]*$` even though `getprop` is
  executed with the key as a discrete argument and no shell is involved. It costs one regex and
  turns a class of question into a non-question.
- **Photos require the token** like everything else. The download route is not a loophole.

What is deliberately *not* done: TLS. A self-signed certificate on a LAN service buys warnings rather
than security, and doing it properly needs a trust story this assignment does not call for. The
README states the exposure plainly instead.

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

Zero-touch *is* achievable, but only by holding privilege this app deliberately does not have:

| Route | What it gives | What it costs |
|---|---|---|
| **Device Owner** | `DevicePolicyManager.setPermissionGrantState` silently grants `CAMERA` and `POST_NOTIFICATIONS` to itself. How MDM products and device farms do it. | Provisioning needs a device with **no accounts configured**, so in practice a factory-reset handset; and `SYSTEM_ALERT_WINDOW` is an *appop*, not a runtime permission, so it is not covered. Removing a device owner needs `dpm remove-active-admin` or a factory reset. |
| **Platform-signed system app** | Everything pre-granted at first boot via a `default-permissions` XML, and privileged apps are not subject to the same background-launch rule — the setup screen disappears entirely. | Needs an AOSP tree or the platform signing key. |
| **`adb shell pm grant`** | One command per permission. | It is adb, which this project excludes by design — and it is **refused outright by ColorOS**, as the realme handset demonstrated. Not dependable across a fleet. |

The middle row is the version that would fully satisfy the assignment's "no GUI", and it is worth
saying plainly that it is unreachable from an APK someone sideloads. The setup screen is not a
shortcut around the permission model; it is what the permission model requires.

---

## 8. Storage

Captures go to `getExternalFilesDir(Pictures)/cam-remote/`, which needs **no storage permission from
API 29** and is removed cleanly when the app is uninstalled. `WRITE_EXTERNAL_STORAGE` would be the
old answer and is not needed. Indexing a photo into the gallery is available per-request
(`--gallery`) rather than by default, so the operator decides whether someone's camera roll fills up
with images they did not take.

The photo index is **persisted** as JSON lines rather than kept in memory, because a foreground
service is long-lived but not immortal: a download URL handed to the control machine should keep
working after Android restarts the process. Entries whose files have gone are dropped on load, so the
file cannot grow without bound.

---

## 9. Wiring

**Manual constructor injection through `AppContainer`.** Roughly twenty objects, all singletons, no
scopes beyond the service's. Hilt would add annotation processing and indirection in exchange for
saving a file that is worth reading: as it stands, the whole composition of the application is one
page, and the command list is the complete answer to "what can this thing do?".

One thing this got wrong first, and it is worth recording because it is the sort of bug the rest of
the strategy cannot catch. The setup screen and the service each constructed their own container. It
compiled, it unit-tested clean, and it failed on the handset: tapping **Pair** opened a pairing window
on the activity's copy while the HTTP server consulted its own and refused every request. The fix was
not to be careful — it was to make the constructor private and hand out a single instance, so the
shape that caused it no longer exists.

---

## 10. Testing

**Test-driven throughout, red then green, one slice at a time.** The git history shows it: each
capability is a `test:` commit followed by a `feat:` commit.

TDD only works on code that runs in milliseconds without a device — which is exactly what the
hexagon delivers. All the decision-making lives in `:core` with no Android imports: target
resolution, precondition checks, path validation, key sanitising, error mapping, filename
generation, mDNS parsing. Every port has a fake. That is 119 tests in `:core` that run in about a
second, plus 26 in `:app` for the transport and the filesystem store, and 65 for the Python client.

The adapters left over are three-to-ten-line pass-throughs with no branching. `CameraAppLauncher` is
the clearest case: a pure function produces a `LaunchSpec` — tested against the no-camera-app and
missing-permission cases — and a trivial shell turns it into an `Intent`.

**Three things this structurally cannot cover**, named rather than papered over:

1. that a real sensor produces a real JPEG,
2. that the OS permits the background activity launch on a given Android version,
3. that mDNS traverses a physical network.

Those are the five instrumented tests and the manual run, and both were done on a real Realme
RMX3563 on Android 14. The instrumented capture test asserts the JPEG magic bytes, because "a file
exists" is not the same claim as "a photograph was taken".

Two things learned from running them on a real OEM device are baked into the suite.
`GrantPermissionRule` is not portable — ColorOS refuses runtime grants from UiAutomation as well as
from the shell — so the suite attempts the grant and skips the camera test with an explanation
rather than failing every test in the class. And an app may not make cleartext HTTP requests since
API 28, so the test that connects to its own server speaks HTTP over a bare socket rather than
loosening the shipped app's network policy to suit a test.

The mDNS parser is tested against a **real 274-byte response captured from the handset**, not a
packet written to match the parser — so it meets genuine compression pointers and a SRV record whose
target is resolved by a separate A record.

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

The **mDNS browser** is the one substantial piece. Only the browsing half is implemented — enough to
ask one question and read the answer. Queries set the unicast-response bit so replies arrive on an
ephemeral port, avoiding a fight with the system responder that already owns port 5353 on macOS. Every
parsing function is total: anything on the network can send anything to that socket, so a malformed
datagram yields no results rather than a stack trace mid-command.

---

## 12. Known limitations

- **A busy camera costs a full timeout.** If another app holds the sensor, CameraX blocks rather than
  failing fast, so `take-picture` returns `TIMEOUT` after 45 seconds. Correct, but slow; there is no
  cheap "is the camera free?" check to pre-empt it with.
- **The lens hint on `camera.open` is best-effort.** No platform contract obliges a camera app to
  honour it. The rear-camera *requirement* is met by `camera.capture`, which drives the sensor
  directly and depends on nobody's goodwill.
- **OEM process killers** can stop the agent regardless of Android's own rules. Opening the app
  restarts it, and it restarts after a reboot, but a vendor protected-apps list may still be needed.
- **No TLS**, as discussed above.
- **One agent per control machine** in the config file. `--host` covers the rest; a named-profiles
  file would be the natural next step.
