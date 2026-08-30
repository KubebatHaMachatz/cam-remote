# Manual testing instructions

A step-by-step walkthrough for running `camremote` by hand and verifying each command actually did
what it claims. Assumes the agent is already installed and the app has been opened on the device at
least once (camera permission, notifications, "Display over other apps" and the battery exemption
all granted) — see the [README](../README.md#set-up-the-device) if that part is not done yet. There
is no separate "switch the agent on" step: opening the app starts it.

Every command below is `python -m camremote <subcommand>` or the equivalent `./scripts/camremote
<subcommand>` wrapper. Run from the repository root; the wrapper handles the import path for you.

```bash
cd cam-remote
./scripts/camremote --help          # lists every subcommand
./scripts/camremote <verb> --help   # flags for one subcommand
```

Global flags, valid before the subcommand name, apply to every command below:

| Flag | Meaning |
|---|---|
| `--host` | Agent address. Skips mDNS discovery when given. |
| `--port` | Agent port. Default `8099`. |
| `--timeout` | Seconds to wait for a reply. Default `60`. |
| `--config` | Config file path. Default `~/.camremote.toml`. |
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
  granted — skip ahead to [`status`](#3-status--confirm-the-device-and-its-readiness) to check.

---

## 1. `discover` — find the agent on the network

```bash
./scripts/camremote discover
```

**What it does:** sends an mDNS query for `_camremote._tcp` and lists every agent that answers, for
up to 3 seconds (`--timeout` to change it).

**Expected output:**

```
realme RMX3563 at 10.0.0.4:8099
```

**How to verify:** the IP address printed should match the address shown in the phone's
notification shade ("Accepting commands on `<ip>:<port>`"). If they differ, something else on the
network is also serving the service type — unlikely, but worth a second look.

**If nothing is printed and the exit code is `3`:** this is a normal result on networks that block
multicast (many corporate and guest Wi-Fi networks do). It does **not** mean the agent is broken —
read the address directly off the phone's screen and pass it explicitly to every later command with
`--host <ip>`, e.g. `./scripts/camremote --host 10.0.0.4 status`.

```bash
./scripts/camremote --json discover     # machine-readable form
```

---

## 2. `pair` — remember the agent's address

No physical action on the phone is needed for this one — there is no code, no token, no handshake.
`pair` just confirms the agent answers and writes its address to disk so later commands do not pay
the mDNS round trip every time.

```bash
./scripts/camremote pair
```

**Expected output:**

```
Found realme RMX3563 at http://10.0.0.4:8099
Address saved to /Users/you/.camremote.toml
```

**How to verify:**

```bash
cat ~/.camremote.toml
```

should show a `host` and `port` matching the address `discover` reported. Every command after this
point uses it automatically without needing `--host` — but skipping `pair` entirely also works fine,
since every command falls back to a live discovery when nothing is configured.

**If it fails with `Could not reach the agent`:** the address in `--host` (or a stale
`~/.camremote.toml`) does not answer. Run `discover` again, or pull down the notification shade on
the phone for the current address.

---

## 3. `status` — confirm the device and its readiness

```bash
./scripts/camremote status
```

**Expected output (fully set up):**

```
realme RMX3563 (Android 14, API 34)
Rear camera: yes
Setup complete: every command is available.
```

**Expected output (missing a grant):**

```
samsung SM-S921B (Android 14, API 34)
Rear camera: yes
Setup incomplete. Missing on the device: canDrawOverlays
Open cam-remote on the handset and grant the items listed above.
```

**How to verify:** the model and Android version should match the physical device in front of you.
Run this first after any setup change — it is the fastest way to confirm a permission grant actually
took effect before testing the command that depends on it.

```bash
./scripts/camremote --json status
```
gives the full JSON, including every individual permission flag (`camera`, `notifications`,
`canDrawOverlays`, `ignoringBatteryOptimizations`) rather than just the missing list.

---

## 4. `system-ping` — liveness and clock check

```bash
./scripts/camremote system-ping
```

**Expected output:**

```
Agent responded in 2 ms (device clock 1787944763154)
```

**How to verify:** the round-trip time should be small (single-digit to low-double-digit
milliseconds) on the same LAN — anything in the seconds suggests a Wi-Fi problem, not an agent
problem. Convert the millisecond timestamp to a date to sanity-check the phone's clock:

```bash
python3 -c "import datetime; print(datetime.datetime.fromtimestamp(1787944763154/1000))"
```

It should print roughly the current date and time. A wildly wrong clock explains confusing capture
timestamps later.

---

## 5. `commands` — the live command catalog

```bash
./scripts/camremote commands
```

**Expected output:**

```
camera.capture - Take a still photograph with the rear camera and save it on the device.
    path (string, optional): Destination directory. Must be inside the agent's writable roots.
    filename (string, optional): Bare filename. Defaults to a UTC timestamp.
    jpegQuality (int, optional, default 95): JPEG quality, 1-100.
    publishToGallery (boolean, optional, default false): Also index the photo in MediaStore...
camera.open - Open the device's camera app. The lens hint is best-effort and app-dependent.
    lens (string, optional): 'front' or 'rear'. A hint only; camera apps are free to ignore it.
    package (string, optional): Open a specific camera app instead of the device default.
device.getprop - Read one or more Android system properties.
    key (string, optional): A single property name, e.g. ro.product.model.
    keys (string_list, optional): Several property names to read in one request...
system.commands - List every command this agent supports, with its parameters.
system.ping - Check that the agent is reachable and report the device clock.
system.status - Report the device, its permissions, and whether the agent is fully set up.
```

(`camera.apps` also appears; the exact set reflects whatever is registered in `AppContainer.kt` on
the device, so treat this output as the source of truth rather than the list above.)

**How to verify:** this is read live from the device, not from a hardcoded list on the client side —
if you add a command to the Android app and reinstall without touching the Python client at all, it
should appear here automatically. That is the extensibility claim; this command is how to prove it.

---

## 6. `getprop` — the assignment's property-fetch requirement

```bash
./scripts/camremote getprop ro.product.model
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
./scripts/camremote getprop ro.product.manufacturer ro.build.version.release ro.build.version.sdk
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
./scripts/camremote getprop ro.this.does.not.exist
```

```
ro.this.does.not.exist = (not set)
```

This must print `(not set)`, not a blank value and not an error — the agent deliberately reports an
absent property as `null` rather than an empty string, since `getprop` cannot otherwise distinguish
"unset" from "set to empty".

**Rejected input — a property name is not free text:**

```bash
./scripts/camremote getprop "ro.product.model; reboot"
```

```
error [INVALID_PARAMS]: 'ro.product.model; reboot' is not a valid property name; expected characters A-Z a-z 0-9 . _ -
```

Exit code `1`. This confirms the agent validates property names server-side rather than trusting
whatever the client sends — worth testing explicitly since it demonstrates the "handle...securely"
half of the assignment for this command.

---

## 7. `open-camera` — the assignment's "open a camera" requirement

```bash
./scripts/camremote open-camera
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
./scripts/camremote --json open-camera
```
adds `strategy` and `preinstalled`/`defaultHandler` fields — useful when comparing behaviour across
devices; see [`camera-apps`](#8-camera-apps-diagnose-which-camera-app-would-be-chosen) below for the
full picture without actually opening anything.

**With a lens hint:**

```bash
./scripts/camremote open-camera --lens front
```

Best-effort only — the camera app decides whether to honour it. Verify by eye whether the front or
rear preview appears; do not treat a mismatch as a bug in the agent, since this is documented as a
hint the camera app is free to ignore.

**If the overlay permission is missing:**

```
error [PRECONDITION_FAILED]: Android will not let a background app start an activity without the overlay permission
  try: Open cam-remote on the device and grant "Display over other apps"
```

Exit code `1`. Grant the permission on the phone (Settings → Apps → cam-remote → Display over other
apps, or tap the app's icon to be walked through it), then retry.

---

## 8. `camera-apps` — diagnose which camera app would be chosen

```bash
./scripts/camremote camera-apps
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

## 9. `take-picture` — the assignment's rear-camera capture requirement

This is the command that most needs the resulting file inspected, not just the terminal output.

```bash
./scripts/camremote take-picture --out ./shots
```

**Expected output:**

```
Captured 2448x3264, 2.98 MB in 1538 ms
On the device: /storage/emulated/0/Android/data/com.camremote.app/files/Pictures/cam-remote/camremote-20260828-191555-123.jpg
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
./scripts/camremote take-picture --out ./shots --filename door --quality 60 --gallery
```

```
Captured 2448x3264, 1.10 MB in 1204 ms
On the device: /storage/emulated/0/Android/data/com.camremote.app/files/Pictures/cam-remote/door.jpg
Saved to: shots/door.jpg
```

Verify: the filename is exactly `door.jpg` (no timestamp appended, confirming `--filename` was
honoured); the file size is meaningfully smaller than a `--quality 95` capture of the same scene
(confirming the quality parameter took effect); and, with `--gallery`, the photo also shows up in the
phone's own Gallery / Photos app (confirming `publishToGallery` reached MediaStore).

**Leaving the photo on the device:**

```bash
./scripts/camremote take-picture --no-download
```

The output stops after the "On the device:" line — no "Saved to:" line, and nothing appears in
`./shots`. Verify by checking that directory is unchanged (`ls shots/`) before and after.

**A destination outside the allowed roots (should be rejected):**

```bash
./scripts/camremote take-picture --path /etc
```

```
error [INVALID_PARAMS]: Parameter 'path' must be inside one of: ...
```

Exit code `1`, and — importantly — nothing should have been written anywhere on the device. This
confirms the path-confinement security check described in
[DESIGN.md](DESIGN.md#7-security) rejects the request before the camera is even touched.

**If another app is holding the camera** (e.g. you just ran `open-camera` and the camera app is
still on screen):

```
error [TIMEOUT]: Command 'camera.capture' exceeded its 45s budget
  try: The CAMERA may be busy with another request; retry shortly
```

Exit code `1`, after roughly a 45-second wait. Close the camera app on the phone (or press the phone's
home button) and retry — it should now succeed in a couple of seconds.

---

## 10. `device-report` — everything about a device in one go

```bash
./scripts/camremote device-report --out matrix/my-device.json
```

**Expected output:** a longer, sectioned summary — device model and Android version, rear-camera
availability, setup completeness, the full camera-app breakdown (same content as `camera-apps`), the
surveyed build properties, and the command catalog — followed by:

```
Full report written to matrix/my-device.json
```

**How to verify:**

```bash
cat matrix/my-device.json | python3 -m json.tool | head -30
```

should show a well-formed JSON document with `agent`, `status`, `cameraApps`, `properties`, and
`commands` top-level keys. This command is the fastest single check when testing a **new** device for
the first time — it runs four underlying commands in sequence and keeps going even if one of them
fails (each failure is recorded inline as an `"error"` object in that section, rather than aborting
the whole report), so it is useful even on a half-configured device.

Run it with `--json` for the raw combined blob on stdout instead of (or in addition to) writing a
file:

```bash
./scripts/camremote --json device-report
```

---

## 11. Verifying failure paths deliberately

A command that only ever succeeds hasn't been tested. These are worth running once to confirm the
agent's error-reporting behaves as documented, not just its happy path.

**Confirm there really is no credential to get wrong** — any client on the same network can run any
command, with nothing to type:

```bash
./scripts/camremote --host 10.0.0.4 system-ping
```

should succeed with no prior `pair` and no config file at all. This is the trade recorded in
[DESIGN.md §7](DESIGN.md#7-security): the project assumes one agent and one client share the LAN, so
there is no gate to get past, correctly or otherwise.

**No config and no `--host`, agent unreachable by discovery:**

```bash
mv ~/.camremote.toml /tmp/camremote-backup.toml
./scripts/camremote status
```
```
error: No agent configured and none found on this network. Run 'camremote discover', or pass --host <address>.
```
Exit code `3` — confirms the client refuses to guess an agent rather than silently talking to
whatever answers. Restore the config afterward:
```bash
mv /tmp/camremote-backup.toml ~/.camremote.toml
```

**Unreachable host:**

```bash
./scripts/camremote --host 10.255.255.1 status
```
```
error: Could not reach the agent at 10.255.255.1:8099 (...). Check the device is on the same network with the agent switched on.
  Found these agents on the network:
    realme RMX3563 at 10.0.0.4:8099  ->  --host 10.0.0.4
```
Exit code `3`. The suggestion line only appears if a real agent answers mDNS in the background —
confirms the "point me at what's actually there" behaviour described in
[DEVICES.md](DEVICES.md#diagnosing-a-new-device).

**Unknown command:**

```bash
./scripts/camremote teleport
```
Exit code `2`, with usage text — confirms bad input is rejected before any network call is made.

---

## Quick reference: one command per assignment requirement

```bash
./scripts/camremote open-camera                      # 1. Open a camera
./scripts/camremote take-picture --out ./shots        # 2. Open a camera and take a picture (rear only)
./scripts/camremote getprop ro.product.model          # 3. Fetch device property data
./scripts/camremote --help                            # 4. The control application itself
```

`scripts/demo.sh` runs all of the above in sequence, with pauses where a manual step (closing the
camera app before capture) is needed.
