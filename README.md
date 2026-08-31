# cam-remote

A headless Android agent that can be driven remotely to open the camera app, take a photograph with
the rear camera, and read device properties — together with a Python control application that issues
those commands from another machine.

Written for the pre-interview assignment in [`pre_interview_assignment.md`](pre_interview_assignment.md).
**Just want to run it?** [docs/QUICK-START.md](docs/QUICK-START.md) is a page and a half.

```
$ camremote --host 10.0.0.4 getprop ro.product.model ro.build.version.release
ro.product.model         = RMX3563
ro.build.version.release = 14

$ camremote --host 10.0.0.4 open-camera
Opened com.oplus.camera/com.oplus.camera.component.CameraImageActivity

$ camremote --host 10.0.0.4 take-picture --out ./shots --filename cli-demo
Captured 2448x3264, 2.98 MB in 1538 ms
On the device: Documents/cam-remote/cli-demo.jpg
Saved to: shots/cli-demo.jpg
```

That output is real, from a Realme RMX3563 running Android 14 (API 34). The address is the one the
agent shows in its own notification; nothing had to be paired, saved or discovered first.

## What it does

| Assignment requirement | Command | Notes |
|---|---|---|
| 1. Open a camera | `camremote open-camera` | Launches the device's camera app from a background service |
| 2. Open a camera and take a picture (rear only) | `camremote take-picture` | Headless capture — no preview, no shutter button — and the JPEG is downloaded to the control machine |
| 3. Fetch device property data | `camremote getprop KEY...` | Reads any number of properties in one round trip |
| 4. Control application | `camremote` | Pure standard library; nothing to install |

Plus `status`, `commands` and `camera-apps` for inspecting an agent. `status` is the one to reach
for first: it surveys the whole device — permissions, camera apps, build properties and the
catalog — and `--out` writes the lot as JSON for a compatibility matrix.

## No adb, no UI, no pairing code

The agent is controlled over Wi-Fi, not a USB cable, and the app has no screen for operating it —
there is nothing to configure before it starts serving commands. Three things follow from that:

- **The API is open on the LAN.** No bearer token, no pairing code. The project assumes exactly one
  agent and one client share the network, which is the assignment's own stated scope — see
  [docs/DESIGN.md](docs/DESIGN.md#7-security) for what that trades away and what a real deployment
  would need instead.
- **It stops from its own notification.** With no screen there is nowhere else to switch it off, so
  the ongoing notification carries a **Terminate service** action. Stopping is remembered across a
  reboot; opening the app starts it again.
- **Permissions are granted on demand.** The one screen the app does have draws nothing of its own —
  it exists only to host the native Android dialogs Android itself requires, and it appears exactly
  when a command needs something the device has not granted yet. See
  [Set up the device](#set-up-the-device) below.
- **The address is typed, not discovered.** Every command takes `--host`, and the agent puts its own
  `host:port` in its notification so it can be read straight off the phone. There was an mDNS
  discovery step; it was removed after proving unreliable across the handsets this was tested on —
  [docs/DEVICES.md](docs/DEVICES.md#why-the-client-does-not-discover-the-agent) records what went wrong. An
  address that is sometimes found is worse than one that is always typed.

This also means the project works on handsets where `adb shell pm grant` is blocked outright, which
the ColorOS device this was built against turned out to be.

## Prerequisites

**To build the agent:** JDK 17 or newer, and the Android SDK with `ANDROID_HOME` set (Android Studio
sets this up; otherwise point it at your SDK, or put `sdk.dir=…` in `android/local.properties`).
Gradle arrives through the wrapper, and the build downloads the compile platform itself. Android
Studio also works — open the `android/` directory.

**To run the control application:** Python 3.11 or newer. Nothing else. No virtualenv, no `pip
install`; everything it uses ships with Python.

**To use the two together:** the handset and the control machine on the same Wi-Fi network.

## Build and install

```bash
cd android
./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. Transfer it to the phone —
email, cloud drive, USB file transfer, whatever suits — and tap it. Android will ask you to allow
installing unknown apps from whichever app you used; that is expected for a sideloaded build.

## Set up the device

Tap the **cam-remote** icon once. That is the one unavoidable manual step — Android gives no way to
start an app for the first time without it — and it is also the last one you are likely to need:

1. A system dialog asks for the **camera** permission (and, on Android 13+, **notifications**).
   Allow both.
2. A Settings screen opens for **"Display over other apps"**. Turn it on and go back. This is what
   lets a background process open the camera app at all, and the exemption Android 14 requires
   before a background service may touch the camera.
3. A dialog asks to **stop optimising battery usage**, so the agent keeps answering with the screen
   off. Allow it.

Each step opens as soon as you finish the one before, so a fresh install is set up in a single
sitting — there is no need to reopen the app between them. The app draws nothing of its own at any
point: everything above is a native Android dialog or a system Settings screen.

Declining any step is fine and does not block the rest; that step simply stays missing. Whatever is
outstanding is offered again the next time a command needs it, so `camremote take-picture` with the
camera permission missing puts the dialog back on screen by itself. Tapping the icon again also
picks up wherever setup left off.

`CAMERA` cannot be granted any other way: it is a dangerous runtime permission, and Android is built
so that an app cannot grant itself one, screen or no screen. Zero-touch setup is only possible for a
device-owner or platform-signed app —
[docs/DESIGN.md](docs/DESIGN.md#why-the-agent-cannot-grant-itself-camera-access) covers both routes
and what they cost.

**If you skip this and send a command anyway:** the command fails with a clear error naming what is
missing, *and* the device tries to show you the relevant dialog right then — the agent's own
persistent notification always retargets to the same screen, so tapping it catches you up on
whatever is still outstanding. This is "a user attending to it on the Android side" as it actually
happens: not a separate setup ritual, but a consequence of the first command that needed something.

## Find the agent

Pull down the notification shade on the phone. The agent's ongoing notification reads:

```
cam-remote is running
Accepting commands on 10.0.0.4:8099
```

That address is what every command needs, and `--host` accepts it exactly as written:

```bash
camremote --host 10.0.0.4:8099 status
camremote --host 10.0.0.4 status        # the port defaults to 8099
```

There is no discovery step and nothing to pair. The agent opens a port, serves commands on it, and
says in its notification where it is — that is the whole of it. It advertised itself over mDNS once;
that was removed after proving unreliable on the handsets this was tested against, and reading four
numbers off a notification is not the part of this problem worth automating.
[docs/DEVICES.md](docs/DEVICES.md#why-the-client-does-not-discover-the-agent) has the measurements.

`scripts/camremote` is a small wrapper that puts the package on the import path; `cd python &&
python3 -m camremote …` is exactly equivalent, and `pip install ./python` gives you a `camremote`
command if you prefer.

## Usage

Every one of these needs `--host`; it is left out below only so the lines stay readable.

```bash
camremote status                        # the whole survey: permissions, camera apps, build, catalog
camremote status --out d.json           # the same, also written as JSON for a device matrix
camremote commands                      # the catalog, straight from the device
camremote getprop ro.product.model      # one property, or several at once
camremote open-camera                   # open the camera app
camremote open-camera --lens front      # best-effort hint; camera apps may ignore it
camremote take-picture --out ./shots    # capture and download
camremote take-picture --filename door --quality 80 --path reports
camremote take-picture --no-download    # leave it on the device
camremote camera-apps                   # every camera app, and which one open-camera would use
```

So a real invocation is `camremote --host 10.0.0.4 status`, and `--host` accepts the `ip:port` form
the notification shows. The other global options are `--port` (when the address does not already
name one), `--timeout`, and `--json` for machine-readable output.

Exit codes, for scripting:

| Code | Meaning |
|---|---|
| 0 | The command succeeded |
| 1 | The agent was reached and reported a failure |
| 2 | The command line was wrong |
| 3 | No agent could be reached |

`scripts/demo.sh` walks through the three assignment features, either side of `status` and
`commands`. It runs them 3-1-2 rather than 1-2-3 on purpose: opening the camera app leaves it
holding the sensor, so the capture has to come after it. The remaining diagnostic, `camera-apps`, is not in it;
`docs/DEVICES.md` covers it.

## Tests

```bash
cd android && ./gradlew :core:test :app:testDebugUnitTest   # 187 unit tests
cd python  && python3 -m unittest discover -s tests -t .    # 59 unit tests
```

Both suites run on a desktop with no device attached and no packages installed. The Python tests use
`unittest` rather than pytest for the same reason the client has no dependencies.

With a handset connected over USB:

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest      # 7 instrumented tests
```

Those seven cover only what a desktop JVM structurally cannot: that a real sensor produces a real
JPEG, that `getprop` reads the real property store, and that the server answers on a real socket.
See [docs/DESIGN.md](docs/DESIGN.md#10-testing) for why the split falls exactly there.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Nothing answers on `--host` | Check the address against the agent's notification, which rewrites itself when the device's address changes. Confirm with `curl http://<ip>:8099/v1/health`. |
| `PRECONDITION_FAILED` from `open-camera` | "Display over other apps" is not granted. Tap the app's icon (or its notification) to be walked through what is still missing. |
| `DEVICE_ERROR: No installed app handles any known camera intent (tried ...)` | The device has no camera app — common on bare AOSP images. `take-picture` still works; it drives the sensor directly. See [docs/DEVICES.md](docs/DEVICES.md). |
| `PERMISSION_DENIED` from `take-picture` | The camera permission is missing. `camremote status` lists exactly what is missing, and the command itself tries to prompt the device for it. |
| Nothing seems to happen on the device | `adb logcat -s CamRemote` shows every command as it arrives and its outcome, including the file a capture wrote. If nothing appears, the request never reached the agent. |
| `TIMEOUT` from `take-picture` | Something else holds the camera — most often the camera app that `open-camera` just launched. Close it and retry. If it persists right after granting the camera permission, reopen cam-remote once: a foreground service's type is fixed when it starts, and reopening lets it re-assert now that the permission exists. |
| Stops answering when the screen is off | Grant "Ignore battery optimisation" (tap the app's icon to be prompted for it). Some OEM builds (Xiaomi, Huawei, realme) kill background services regardless of Android's own rules; on those, add cam-remote to the vendor's own protected-apps list. |
| Nothing answers, agent seemingly not running | Tap the app's icon: it restarts an agent the system has killed. It also restarts itself after a reboot, once it has been opened at least once. |

## Controlling it from outside the local network

The transport is plain TCP, so it needs no code change: install an overlay network such as Tailscale
on both the phone and the control machine, and pass the phone's overlay address as `--host`. It then
works from anywhere, over WireGuard, with no port forwarding on your router.

If you would rather the agent reach *out* to a broker instead — MQTT, or a relay you host — that is a
new transport rather than a new feature, and
[docs/EXTENDING.md](docs/EXTENDING.md#adding-a-transport) walks through where it plugs in.

## Where to read next

| Document | What it covers |
|---|---|
| [docs/QUICK-START.md](docs/QUICK-START.md) | Install, grant, run — the shortest path to a photograph |
| [docs/OVERVIEW-HE.md](docs/OVERVIEW-HE.md) | סקירה קצרה בעברית — the same overview in Hebrew |
| [docs/DESIGN.md](docs/DESIGN.md) | Every significant decision and why it was made, including the ones rejected |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The layout of the code and the path a request takes through it |
| [docs/CODE-TOUR-ANDROID.md](docs/CODE-TOUR-ANDROID.md) | A reading order through the agent, file by file, for a first look |
| [docs/CODE-TOUR-PYTHON.md](docs/CODE-TOUR-PYTHON.md) | The same for the control application |
| [docs/PACKAGES.md](docs/PACKAGES.md) | What each package is responsible for, and where a change belongs |
| [docs/EXTENDING.md](docs/EXTENDING.md) | Adding a command, adding a transport, swapping an implementation |
| [docs/DEVICES.md](docs/DEVICES.md) | What varies between handsets, per-OEM notes, and how to diagnose a new one |
| [docs/INSTRUCTIONS.md](docs/INSTRUCTIONS.md) | Manual testing walkthrough — every `camremote` command with expected output and how to verify it |

## Repository layout

```
android/          Gradle project
  core/           Plain Kotlin/JVM: protocol, commands, ports, decision logic. No Android.
  app/            The Android application: adapters, HTTP server, service, the one trampoline screen.
python/
  camremote/      The control application. Standard library only.
  tests/
docs/             Design, architecture, extension guide, device notes, manual testing.
scripts/          camremote launcher, and a demo walkthrough.
```
