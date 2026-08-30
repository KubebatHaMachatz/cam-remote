# cam-remote

A headless Android agent that can be driven remotely to open the camera app, take a photograph with
the rear camera, and read device properties — together with a Python control application that issues
those commands from another machine.

Written for the pre-interview assignment in [`pre_interview_assignment.md`](pre_interview_assignment.md).

```
$ camremote discover
realme RMX3563 at 10.0.0.4:8099

$ camremote getprop ro.product.model ro.build.version.release
ro.product.model         = RMX3563
ro.build.version.release = 14

$ camremote open-camera
Opened com.oplus.camera/com.oplus.camera.component.CameraImageActivity

$ camremote take-picture --out ./shots
Captured 2448x3264, 2.98 MB in 1538 ms
On the device: Documents/cam-remote/cli-demo.jpg
Saved to: shots/cli-demo.jpg
```

That output is real, from a Realme RMX3563 running Android 14 (API 34). No pairing step ran first —
none is needed.

## What it does

| Assignment requirement | Command | Notes |
|---|---|---|
| 1. Open a camera | `camremote open-camera` | Launches the device's camera app from a background service |
| 2. Open a camera and take a picture (rear only) | `camremote take-picture` | Headless capture — no preview, no shutter button — and the JPEG is downloaded to the control machine |
| 3. Fetch device property data | `camremote getprop KEY...` | Reads any number of properties in one round trip |
| 4. Control application | `camremote` | Pure standard library; nothing to install |

Plus `discover`, `pair`, `status`, `commands`, `camera-apps`, `device-report` and `system-ping`
for finding, remembering, inspecting and surveying an agent.

## No adb, no UI, no pairing code

The agent is controlled over Wi-Fi, not a USB cable, and the app has no screen for operating it —
there is nothing to configure before it starts serving commands. Three things follow from that:

- **The API is open on the LAN.** No bearer token, no pairing code. The project assumes exactly one
  agent and one client share the network, which is the assignment's own stated scope — see
  [docs/DESIGN.md](docs/DESIGN.md#7-security) for what that trades away and what a real deployment
  would need instead.
- **Permissions are granted on demand.** The one screen the app does have draws nothing of its own —
  it exists only to host the native Android dialogs Android itself requires, and it appears exactly
  when a command needs something the device has not granted yet. See
  [Set up the device](#set-up-the-device) below.
- **Discovery is automatic.** `camremote discover` finds the agent over mDNS; nothing has to be typed
  on the phone to make that work.

This also means the project works on handsets where `adb shell pm grant` is blocked outright, which
the ColorOS device this was built against turned out to be.

## Prerequisites

**To build the agent:** JDK 17 or newer, and the Android SDK with `ANDROID_HOME` set (Android Studio
sets this up; otherwise point it at your SDK, or put `sdk.dir=…` in `android/local.properties`).
Gradle arrives through the wrapper, and the build downloads the compile platform itself. Android
Studio also works — open the `android/` directory.

**To run the control application:** Python 3.11 or newer. Nothing else. No virtualenv, no `pip
install`; `tomllib` and everything else it uses ship with Python.

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

The app draws nothing of its own at any point — everything above is a native Android dialog or a
system Settings screen. If you miss one, or the device asks for something else later (typically
"Ignore battery optimisation", so the agent keeps answering with the screen off), tap the icon again
to pick up wherever setup left off.

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

```bash
camremote discover
```
```
realme RMX3563 at 10.0.0.4:8099
```

If nothing answers — common on networks that block multicast — pull down the notification shade on
the phone: the agent's ongoing notification always shows its current `ip:port`. Pass it directly:

```bash
camremote --host 10.0.0.4 status
```

Optionally, remember it so you do not have to type `--host` every time:

```bash
camremote pair
```
```
Found realme RMX3563 at http://10.0.0.4:8099
Address saved to /Users/you/.camremote.toml
```

No code, no handshake — `pair` here just confirms the agent answers and writes its address to
`~/.camremote.toml`. `scripts/camremote` is a two-line wrapper that puts the package on the import
path; `cd python && python3 -m camremote …` is exactly equivalent, and `pip install ./python` gives
you a `camremote` command if you prefer.

## Usage

```bash
camremote discover                      # find agents over mDNS
camremote pair                          # remember the agent's address (optional)
camremote status                        # device, permissions, readiness
camremote commands                      # the catalog, straight from the device
camremote getprop ro.product.model      # one property, or several at once
camremote open-camera                   # open the camera app
camremote open-camera --lens front      # best-effort hint; camera apps may ignore it
camremote take-picture --out ./shots    # capture and download
camremote take-picture --filename door --quality 80 --path reports
camremote take-picture --no-download    # leave it on the device
camremote camera-apps                   # every camera app, and which one open-camera would use
camremote device-report --out d.json    # everything about a device, for a compatibility matrix
```

Global options: `--host`, `--port`, `--timeout`, `--config`, and `--json` for machine-readable
output.

Exit codes, for scripting:

| Code | Meaning |
|---|---|
| 0 | The command succeeded |
| 1 | The agent was reached and reported a failure |
| 2 | The command line was wrong |
| 3 | No agent could be reached |

`scripts/demo.sh` walks through every capability in the order the assignment lists them.

## Tests

```bash
cd android && ./gradlew :core:test :app:testDebugUnitTest   # 151 unit tests
cd python  && python3 -m unittest discover -s tests -t .    # 69 unit tests
```

Both suites run on a desktop with no device attached and no packages installed. The Python tests use
`unittest` rather than pytest for the same reason the client has no dependencies.

With a handset connected over USB:

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest      # 5 instrumented tests
```

Those five cover only what a desktop JVM structurally cannot: that a real sensor produces a real
JPEG, that `getprop` reads the real property store, and that the server answers on a real socket.
See [docs/DESIGN.md](docs/DESIGN.md#testing) for why the split falls exactly there.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `discover` finds nothing | Many networks block multicast and guest networks isolate clients entirely. Pull down the notification shade on the phone for the address and pass `--host 10.0.0.x`. |
| `PRECONDITION_FAILED` from `open-camera` | "Display over other apps" is not granted. Tap the app's icon (or its notification) to be walked through what is still missing. |
| `DEVICE_ERROR: no installed app handles any known camera intent` | The device has no camera app — common on bare AOSP images. `take-picture` still works; it drives the sensor directly. See [docs/DEVICES.md](docs/DEVICES.md). |
| `PERMISSION_DENIED` from `take-picture` | The camera permission is missing. `camremote status` lists exactly what is missing, and the command itself tries to prompt the device for it. |
| `TIMEOUT` from `take-picture` | Something else holds the camera — most often the camera app that `open-camera` just launched. Close it and retry. |
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
| [docs/DESIGN.md](docs/DESIGN.md) | Every significant decision and why it was made, including the ones rejected |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | The layout of the code and the path a request takes through it |
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
docs/             Design, architecture, and extension guide.
scripts/          camremote launcher, and a demo walkthrough.
```
