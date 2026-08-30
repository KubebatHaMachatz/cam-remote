# Manual testing instructions

A step-by-step walkthrough for running `camremote` by hand and verifying each command actually did
what it claims. Assumes the agent is already installed and the app has been opened on the device at
least once (camera permission, notifications, "Display over other apps" and the battery exemption
all granted) — see the [README](../README.md#set-up-the-device) if that part is not done yet. There
is no separate "switch the agent on" step: opening the app starts it.

Every command below is `python -m camremote --host <address> <subcommand>` or the equivalent
`./scripts/camremote --host <address> <subcommand>` wrapper. Run from the repository root; the
wrapper handles the import path for you. Where the address comes from is
[section 1](#1-reading-the-agents-address-off-the-phone).

```bash
cd cam-remote
./scripts/camremote --help          # lists every subcommand
./scripts/camremote <verb> --help   # flags for one subcommand
```

Global flags, valid before the subcommand name, apply to every command below:

| Flag | Meaning |
|---|---|
| `--host` | **Required.** The agent's address, as shown in the notification on the device. Accepts a bare address (`10.0.0.4`) or one carrying a port (`10.0.0.4:8099`). |
| `--port` | Agent port. Default `8099`. Ignored when `--host` already names a port. |
| `--timeout` | Seconds to wait for a reply. Default `60` — generous, because a capture legitimately takes seconds. |
| `--json` | Print the agent's raw JSON instead of the human-readable summary. |

Exit codes, useful when scripting a check:

| Code | Meaning |
|---|---|
| `0` | Command succeeded |
| `1` | The agent was reached and reported a failure |
| `2` | The command line was wrong |
| `3` | No agent could be reached |

Check the exit code of any command with `echo $?` immediately after running it.

---

## 0. Prerequisites

- Python 3.11+, nothing else installed (`python3 --version` to confirm).
- The phone and this machine on the same Wi-Fi network.
- The app opened on the phone at least once, so the agent is running and its permissions are
  granted — skip ahead to [`status`](#2-status--confirm-the-device-and-its-readiness) to check.

---

## 1. Reading the agent's address off the phone

There is no discovery step and nothing to pair: the client never guesses which agent it is talking
to, so `--host` is required on every command below. That sounds like extra typing, and it is, but it
buys certainty — a client that only ever talks to the address you gave it cannot quietly attach
itself to the wrong handset, and there is no stale saved address to go looking for when something
stops answering.

The address is on the phone. Pull down the notification shade; the agent's ongoing notification
reads:

```
Accepting commands on 10.0.0.4:8099
```

Pass that string to `--host` exactly as it appears — the whole `host:port` pair is accepted, so
there is nothing to take apart:

```bash
./scripts/camremote --host 10.0.0.4:8099 status
```

A bare address works too, and falls back to the default port `8099`:

```bash
./scripts/camremote --host 10.0.0.4 status
```

The two are equivalent as long as the agent is on the default port. When the address carries a port
it wins outright — `--port` is only consulted when the address does not already name one, so
`--host 10.0.0.4:8099 --port 9000` still talks to `8099`.

The examples from here on use `10.0.0.4`, the realme handset this walkthrough was written against.
Substitute whatever your own notification says; the address changes whenever the phone's DHCP lease
does, so it is worth re-reading the shade rather than trusting a value from yesterday.

---

## 2. `status` — confirm the device and its readiness

```bash
./scripts/camremote --host 10.0.0.4 status
```

**Expected output (fully set up):** four sections, gathered in four requests.

```
realme RMX3563 (Android 14, API 34)
Rear camera: yes
Answered in 8 ms; device clock 2026-08-30 18:40:30 (in step with this machine)

Permissions:
  camera                        granted
  notifications                 granted
  canDrawOverlays               granted
  ignoringBatteryOptimizations  granted

Camera apps
  camera.open would use: still_image_camera → com.oplus.camera/.Camera
  still_image_camera (android.media.action.STILL_IMAGE_CAMERA): 1 handler(s)
      com.oplus.camera/.Camera  [preinstalled, default handler]
  app_camera_category (android.intent.action.MAIN): 1 handler(s)
      com.oplus.camera/.Camera  [preinstalled]
  image_capture (android.media.action.IMAGE_CAPTURE): 1 handler(s)
      com.oplus.camera/.Camera  [preinstalled, default handler]

Build
  ro.product.manufacturer         = realme
  ro.product.model                = RMX3563
  ro.build.version.release        = 14
  ro.build.version.sdk            = 34
  ro.build.version.security_patch = 2024-03-01
  …ten more…

Commands (6): camera.apps, camera.capture, camera.open, device.getprop, system.commands, system.status

Setup complete: every command is available.
```

**Expected output (missing a grant):** identical but for the permission block and the last line.

```
samsung SM-S921B (Android 14, API 34)
Rear camera: yes
Answered in 9 ms; device clock 2026-08-30 18:15:25 (in step with this machine)

Permissions:
  camera                        granted
  notifications                 granted
  canDrawOverlays               MISSING  - blocks open-camera, which starts an app from the background
  ignoringBatteryOptimizations  granted

…camera apps, build and catalog as above…

Setup incomplete. Missing on the device: canDrawOverlays
Open cam-remote on the handset and grant the items listed above.
```

**How to verify:** the model and Android version should match the physical device in front of you.
Run this first after any setup change — it is the fastest way to confirm a permission grant actually
took effect before testing the command that depends on it.

The round-trip figure should be small on the same LAN — single digits to low double digits of
milliseconds. Seconds means a Wi-Fi problem rather than an agent problem. The clock line compares
the handset against this machine and says only that the two disagree, never which is wrong; a
handset an hour out writes capture timestamps that make no sense a week later, and this is the
cheapest place to notice. Anything within five seconds reads as *in step*, because below that the
difference cannot be told from the round trip that measured it.

**The whole survey, for a device matrix.** `status` runs four requests — its own readiness, the
camera-app breakdown, fourteen build properties and the command catalog — and prints them as one
sectioned summary. `--out` writes the same thing as JSON:

```bash
./scripts/camremote --host 10.0.0.4 status --out matrix/my-device.json
cat matrix/my-device.json | python3 -m json.tool | head -30
```

That file has `status`, `cameraApps`, `properties` and `commands` at the top level, which is what
goes in the matrix in `docs/DEVICES.md`. `--json` prints the same blob to stdout instead.

**It keeps going when the device does not.** A section that fails is recorded in place as an
`"error"` object rather than ending the run — if `camera.apps` fails because the camera permission
is missing, the rest still reports, and the failure is part of the answer. A diagnostic that only
works on a healthy device is no diagnostic, and a half-configured device is exactly when this gets
run.

```bash
./scripts/camremote --host 10.0.0.4 --json status
```
gives the same information as raw JSON, for a script that wants to assert on it.

---

## 3. `commands` — the live command catalog

```bash
./scripts/camremote --host 10.0.0.4 commands
```

**Expected output:**

```
camera.apps - List every installed app that could answer a camera intent, and which one camera.open would pick.
camera.capture - Take a still photograph with the rear camera and save it under Documents.
    path (string, optional, default Documents/cam-remote): Destination directory, relative to the device's Documents folder.
    filename (string, optional): Bare filename. Defaults to a UTC timestamp.
    jpegQuality (int, optional, default 95): JPEG quality, 1-100.
camera.open - Open the device's camera app. The lens hint is best-effort and app-dependent.
    lens (string, optional): 'front' or 'rear'. A hint only; camera apps are free to ignore it.
    package (string, optional): Open a specific camera app instead of the device default.
device.getprop - Read one or more Android system properties.
    key (string, optional): A single property name, e.g. ro.product.model.
    keys (string_list, optional): Several property names to read in one request...
system.commands - List every command this agent supports, with its parameters.
system.status - Report the device, its permissions, and whether the agent is fully set up.
```

(The registry sorts by name, so this is the real order. The exact set reflects whatever is
registered in `AppContainer.kt` on the device, so treat this output as the source of truth rather
than the list above.)

**How to verify:** this is read live from the device, not from a hardcoded list on the client side —
if you add a command to the Android app and reinstall without touching the Python client at all, it
should appear here automatically. That is the extensibility claim; this command is how to prove it.

---

## 4. `getprop` — the assignment's property-fetch requirement

```bash
./scripts/camremote --host 10.0.0.4 getprop ro.product.model
```

**Expected output:**

```
ro.product.model = RMX3563
```

**How to verify:** compare against the real value directly on the phone, e.g. in Settings → About
phone, or (if you have adb for your own verification, separate from the app) `adb shell getprop
ro.product.model`.

**Several properties in one request:**

```bash
./scripts/camremote --host 10.0.0.4 getprop ro.product.manufacturer ro.build.version.release ro.build.version.sdk
```

```
ro.product.manufacturer  = realme
ro.build.version.release = 14
ro.build.version.sdk     = 34
```

Confirm it was genuinely one network round trip, not three, by watching the response time — it
should not take noticeably longer than a single-property request.

**An unset property:**

```bash
./scripts/camremote --host 10.0.0.4 getprop ro.this.does.not.exist
```

```
ro.this.does.not.exist = (not set)
```

This must print `(not set)`, not a blank value and not an error — the agent deliberately reports an
absent property as `null` rather than an empty string, since `getprop` cannot otherwise distinguish
"unset" from "set to empty".

**Rejected input — a property name is not free text:**

```bash
./scripts/camremote --host 10.0.0.4 getprop "ro.product.model; reboot"
```

```
error [INVALID_PARAMS]: 'ro.product.model; reboot' is not a valid property name; expected characters A-Z a-z 0-9 . _ -
```

Exit code `1`. This confirms the agent validates property names server-side rather than trusting
whatever the client sends — worth testing explicitly since it demonstrates the "handle...securely"
half of the assignment for this command.

---

## 5. `open-camera` — the assignment's "open a camera" requirement

```bash
./scripts/camremote --host 10.0.0.4 open-camera
```

**Expected output:**

```
Opened com.oplus.camera/com.oplus.camera.component.CameraImageActivity
```

**How to verify — this is the one command you must confirm by looking at the phone.** The camera
app's live viewfinder should appear on the phone's screen within about a second of running the
command, launched from the background with no one touching the phone. Component names vary by
manufacturer (Samsung: `com.sec.android.app.camera/...`); see [DEVICES.md](DEVICES.md) for what to
expect from a given OEM.

```bash
./scripts/camremote --host 10.0.0.4 --json open-camera
```
adds `strategy` and `preinstalled`/`defaultHandler` fields — useful when comparing behaviour across
devices; see [`camera-apps`](#6-camera-apps--diagnose-which-camera-app-would-be-chosen) below for the
full picture without actually opening anything.

**With a lens hint:**

```bash
./scripts/camremote --host 10.0.0.4 open-camera --lens front
```

Best-effort only — the camera app decides whether to honour it. Verify by eye whether the front or
rear preview appears; do not treat a mismatch as a bug in the agent, since this is documented as a
hint the camera app is free to ignore.

**If the overlay permission is missing:**

```
error [PRECONDITION_FAILED]: Android will not let a background app start an activity without the overlay permission
  try: A settings prompt was shown on the device; grant "Display over other apps" there, then retry
```

Exit code `1`. Grant the permission on the phone (Settings → Apps → cam-remote → Display over other
apps, or tap the app's icon to be walked through it), then retry.

---

## 6. `camera-apps` — diagnose which camera app would be chosen

```bash
./scripts/camremote --host 10.0.0.4 camera-apps
```

**Expected output:**

```
camera.open would use: still_image_camera -> com.sec.android.app.camera/com.sec.android.app.camera.executor.AssistantActionActivity
still_image_camera (android.media.action.STILL_IMAGE_CAMERA): 1 handler(s)
    com.sec.android.app.camera/com.sec.android.app.camera.executor.AssistantActionActivity  [preinstalled, default handler]
app_camera_category (android.intent.action.MAIN): 0 handler(s)
image_capture (android.media.action.IMAGE_CAPTURE): 1 handler(s)
    com.sec.android.app.camera/com.sec.android.app.camera.Camera  [preinstalled, default handler]
```

**How to verify:** this does not launch anything — it is a read-only survey. Run it before
`open-camera` on a new device to predict what will happen; the `wouldUseStrategy` /
`wouldUseComponent` line should match what actually opens when you subsequently run `open-camera`.
Useful when a device has more than one camera app installed and you want to know which one will be
picked without triggering a launch.

---

## 7. `take-picture` — the assignment's rear-camera capture requirement

This is the command that most needs the resulting file inspected, not just the terminal output.

```bash
./scripts/camremote --host 10.0.0.4 take-picture --out ./shots
```

**Expected output:**

```
Captured 2448x3264, 2.98 MB in 1538 ms
On the device: Documents/cam-remote/camremote-20260828-191555-123.jpg
Saved to: shots/camremote-20260828-191555-123.jpg
```

**How to verify, in order:**

1. **The file exists and is a real photo:**
   ```bash
   file shots/camremote-*.jpg
   ```
   should report `JPEG image data`, with the width/height matching the numbers printed above.

2. **Open it and look at it.** On macOS: `open shots/camremote-*.jpg`. It should be a real
   photograph taken by the rear camera at the moment the command ran — not a black frame, not a
   placeholder. This is the strongest verification available: the file genuinely came off the sensor.

3. **The size on disk matches what was reported:**
   ```bash
   ls -la shots/camremote-*.jpg
   ```
   The byte count should be close to the "MB" figure in the terminal output (small differences from
   filesystem block rounding are fine; large ones are not).

4. **The capture is rear camera specifically.** There is no automated way to tell "rear" from
   "front" from the JPEG alone on most devices — the practical check is holding the phone so the
   front and rear cameras are pointed at visibly different things, running the command, and
   confirming the photo shows what the rear camera was facing.

**With options:**

```bash
./scripts/camremote --host 10.0.0.4 take-picture --out ./shots --filename door --quality 60 --path reports
```

```
Captured 2448x3264, 1.10 MB in 1204 ms
On the device: Documents/reports/door.jpg
Saved to: shots/door.jpg
```

Verify: the filename is exactly `door.jpg` (no timestamp appended, confirming `--filename` was
honoured); the file size is meaningfully smaller than a `--quality 95` capture of the same scene
(confirming the quality parameter took effect); and that the photo is genuinely reachable on the
handset — open any file manager and look in **Documents / reports**, or check from this machine:

```bash
adb shell ls -l /sdcard/Documents/reports/
```

That last check is the point of the whole storage design: the file is in the user's own Documents
folder, put there by an app holding no storage permission of any kind.

**Leaving the photo on the device:**

```bash
./scripts/camremote --host 10.0.0.4 take-picture --no-download
```

The output stops after the "On the device:" line — no "Saved to:" line, and nothing appears in
`./shots`. Verify by checking that directory is unchanged (`ls shots/`) before and after.

**A destination that escapes Documents (should be rejected):**

```bash
./scripts/camremote --host 10.0.0.4 take-picture --path /etc
```

```
error [INVALID_PARAMS]: Parameter 'path' is a directory inside 'Documents', not an absolute path, got '/etc'
```

Traversal is refused the same way, and neither reaches the sensor — no photograph is taken:

```bash
./scripts/camremote --host 10.0.0.4 take-picture --path ../../escape
```

Exit code `1`, and — importantly — nothing should have been written anywhere on the device. This
confirms the destination check described in [DESIGN.md](DESIGN.md#8-storage) rejects the request
before the camera is even touched.

**If another app is holding the camera** (e.g. you just ran `open-camera` and the camera app is
still on screen):

```
error [TIMEOUT]: Command 'camera.capture' exceeded its 45s budget
  try: The CAMERA may be busy with another request; retry shortly
```

Exit code `1`, after roughly a 45-second wait. Close the camera app on the phone (or press the phone's
home button) and retry — it should now succeed in a couple of seconds.

---

## 8. Watching what the device did

Everything so far reads the agent's answer. This reads the agent's own account, which is the other
half — and the half that is still there tomorrow.

```bash
adb logcat -s CamRemote
```

Leave it running and issue a command from the other machine. Each one appears twice, on arrival and
on completion:

```
I CamRemote: --> camera.capture  id=0ebf744b  params={"filename":"logged"}
I CamRemote: <-- camera.capture  OK  in 2356ms  {"id":"8exHY7TL…","path":"Documents/cam-remote/logged.jpg","sizeBytes":3285774,…}
I CamRemote: --> camera.open  id=247cba03
W CamRemote: <-- camera.open  PRECONDITION_FAILED  in 15ms  Android will not let a background app start an activity without the overlay permission
W CamRemote:     remediation: A settings prompt was shown on the device; grant "Display over other apps" there, then retry
```

**What to look for:** a capture's line names the file it wrote, which is the quickest way to confirm
where a photograph actually landed without going through MediaStore. A command that fails is logged
at `WARN` with the same remediation the client prints, so a filtered log still tells the whole
story. `ERROR` means a defect in the agent rather than a device saying no, and should never appear.

**Why it is worth using:** it distinguishes "the command never arrived" from "the command arrived
and failed", which from the control machine look identical — both are simply an error on the
terminal. If nothing appears here at all, the problem is the network or the address, not the agent.

This is the only place `adb` appears in normal use, and it is a diagnostic rather than part of the
product: nothing about controlling the agent requires a cable.

---

## 9. Verifying failure paths deliberately

A command that only ever succeeds hasn't been tested. These are worth running once to confirm the
agent's error-reporting behaves as documented, not just its happy path.

**Confirm there really is no credential to get wrong** — any client on the same network can run any
command, with no token, code or handshake to present:

```bash
./scripts/camremote --host 10.0.0.4 status
```

should succeed on a machine that has never run `camremote` before, with nothing but the address to
type. This is the trade recorded in [DESIGN.md §7](DESIGN.md#7-security): the project assumes one
agent and one client share the LAN, so there is no gate to get past, correctly or otherwise.

**Omitting `--host` entirely:**

```bash
./scripts/camremote status
```
```
the following arguments are required: --host

usage: camremote [-h] --host ADDRESS [--port PORT] [--timeout TIMEOUT]
                 [--json]
                 COMMAND ...
```
Exit code `2` — a usage error, raised by the argument parser before anything touches the network.
This is the client refusing to guess: with no discovery and nothing saved on disk, there is no
address it could fall back to, and inventing one would only mean talking to whatever happened to
answer.

**A wrong or stale address:**

```bash
./scripts/camremote --host 10.255.255.1 status
```
```
error: Could not reach the agent at 10.255.255.1:8099 (timed out). Check the device is on the same network with the agent switched on.
  The address comes from the agent's notification on the device; check the phone is awake and on the same network.
```
Exit code `3` — a transport failure, distinct from exit `2` above because the command line was fine
and the network was not. The same result appears when a previously good address has gone stale, which
is the common case: the phone took a new DHCP lease, or dropped off Wi-Fi while asleep. Wake the
handset, pull down the notification shade, and use whatever address it now reports. Note that the
timeout is the full `--timeout` (60 seconds by default), so pass a smaller one when deliberately
testing this path.

**Unknown command:**

```bash
./scripts/camremote --host 10.0.0.4 teleport
```
Exit code `2`, with usage text — confirms bad input is rejected before any network call is made.

---

## Quick reference: one command per assignment requirement

```bash
./scripts/camremote --host 10.0.0.4 open-camera                   # 1. Open a camera
./scripts/camremote --host 10.0.0.4 take-picture --out ./shots    # 2. Open a camera and take a picture (rear only)
./scripts/camremote --host 10.0.0.4 getprop ro.product.model      # 3. Fetch device property data
./scripts/camremote --help                                        # 4. The control application itself
```

`scripts/demo.sh` runs all of the above in sequence, with pauses where a manual step (closing the
camera app before capture) is needed. It takes the agent's address as its only argument — the same
string the notification shows — and passes it through as `--host`:

```bash
./scripts/demo.sh 10.0.0.4
```
