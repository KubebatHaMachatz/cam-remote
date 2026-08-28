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
On the device: /storage/emulated/0/Android/data/com.camremote.app/files/Pictures/cam-remote/cli-demo.jpg
Saved to: shots/cli-demo.jpg
```

That output is real, from a Realme RMX3563 running Android 14 (API 34).

## What it does

| Assignment requirement | Command | Notes |
|---|---|---|
| 1. Open a camera | `camremote open-camera` | Launches the device's camera app from a background service |
| 2. Open a camera and take a picture (rear only) | `camremote take-picture` | Headless capture — no preview, no shutter button — and the JPEG is downloaded to the control machine |
| 3. Fetch device property data | `camremote getprop KEY...` | Reads any number of properties in one round trip |
| 4. Control application | `camremote` | Pure standard library; nothing to install |

Plus `discover`, `pair`, `status`, `commands` and `system-ping` for finding, authorising and
inspecting an agent.

## No adb

The agent is controlled over Wi-Fi, not over a USB cable. Nothing in the client, the scripts, or
these instructions calls `adb`: the app is sideloaded, its permissions are granted on its own setup
screen, it advertises itself over mDNS, and it hands the control machine a token when you tap
**Pair**. That is a deliberate constraint — it makes "controlled remotely" mean a real network hop,
and it means the project works on handsets where `adb shell pm grant` is blocked outright, which the
ColorOS device this was built against turned out to be.

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

## Set up the device (once)

Open **cam-remote** on the phone. The screen is not a control panel — there is nothing there for
taking photographs — it exists because Android will not grant these things remotely:

| Grant | Why the agent needs it |
|---|---|
| **Camera** | To take a photograph at all. |
| **Notifications** | The foreground service must show a notification on Android 13+. |
| **Display over other apps** | Twice over: Android blocks a background app from starting an activity, so without it `open-camera` cannot work; and it is the documented exemption from the Android 14 rule against starting a camera-type foreground service from the background. |
| **Ignore battery optimisation** | Keeps the device answering once the screen has been off for a while. |

Then switch **Agent running** on. The screen shows the address it is listening on.

## Pair the control machine

On the phone, tap **Pair a control machine**. That opens a sixty-second, single-use window. Then, on
your machine:

```bash
./scripts/camremote pair
```

```
Paired with http://10.0.0.4:8099
Token saved to /Users/you/.camremote.toml
```

The token is written with `0600` permissions and used for every later request. If you would rather
not run pairing, the token is also printed on the setup screen and can be passed as `--token` or
`CAMREMOTE_TOKEN` — it is four characters, so typing it is no hardship.

> **That short token is a proof-of-concept choice, not a production one.** Four characters is
> guessable by anyone on the same network in under a minute, so do not leave this build running on a
> network you do not trust. Lengthening it is one constant, `Tokens.LENGTH`; the reasoning is in
> [docs/DESIGN.md](docs/DESIGN.md#7-security).

`scripts/camremote` is a two-line wrapper that puts the package on the import path. `cd python &&
python3 -m camremote …` is exactly equivalent, and `pip install ./python` gives you a `camremote`
command if you prefer.

## Usage

```bash
camremote discover                      # find agents over mDNS
camremote status                        # device, permissions, readiness
camremote commands                      # the catalog, straight from the device
camremote getprop ro.product.model      # one property, or several at once
camremote open-camera                   # open the camera app
camremote open-camera --lens front      # best-effort hint; camera apps may ignore it
camremote take-picture --out ./shots    # capture and download
camremote take-picture --filename door --quality 80 --gallery
camremote take-picture --no-download    # leave it on the device
```

Global options: `--host`, `--port`, `--token`, `--timeout`, `--config`, and `--json` for machine-
readable output.

Exit codes, for scripting:

| Code | Meaning |
|---|---|
| 0 | The command succeeded |
| 1 | The agent was reached and reported a failure (including a rejected token) |
| 2 | The command line was wrong |
| 3 | No agent could be reached |

`scripts/demo.sh` walks through every capability in the order the assignment lists them.

## Tests

```bash
cd android && ./gradlew :core:test :app:testDebugUnitTest   # 145 unit tests
cd python  && python3 -m unittest discover -s tests -t .    # 65 unit tests
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
| `discover` finds nothing | Many networks block multicast and guest networks isolate clients entirely. Read the address off the setup screen and pass `--host 10.0.0.x`. |
| `PRECONDITION_FAILED` from `open-camera` | "Display over other apps" is not granted. Open the app and grant it. On ColorOS the Settings link opens the full app list rather than this app — scroll to cam-remote. |
| `DEVICE_ERROR: no installed app handles any known camera intent` | The device has no camera app — common on bare AOSP images. `take-picture` still works; it drives the sensor directly. See [docs/DEVICES.md](docs/DEVICES.md). |
| `PERMISSION_DENIED` from `take-picture` | The camera permission is missing. `camremote status` lists exactly what is missing. |
| `TIMEOUT` from `take-picture` | Something else holds the camera — most often the camera app that `open-camera` just launched. Close it and retry. |
| Stops answering when the screen is off | Grant "Ignore battery optimisation". Some OEM builds (Xiaomi, Huawei, realme) kill background services regardless of Android's own rules; on those, add cam-remote to the vendor's own protected-apps list. |
| Agent switch says on but nothing answers | Open the app: doing so restarts an agent the system has killed. It also restarts by itself after a reboot. |

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

## Repository layout

```
android/          Gradle project
  core/           Plain Kotlin/JVM: protocol, commands, ports, decision logic. No Android.
  app/            The Android application: adapters, HTTP server, service, setup screen.
python/
  camremote/      The control application. Standard library only.
  tests/
docs/             Design, architecture, and extension guide.
scripts/          camremote launcher, and a demo walkthrough.
```
