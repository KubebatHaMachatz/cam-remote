# Device notes

What varies between handsets, what the agent does about it, and what to check when a new device
misbehaves.

## What actually differs

The home-screen launcher is irrelevant here — nothing in this project goes through it. Three other
things vary, and all three are handled rather than assumed:

| Varies | Effect | How it is handled |
|---|---|---|
| **Which app answers the camera intent** | `camera.open` may find nothing | An ordered chain of four strategies; the response says which one worked |
| **OEM background policy** | The agent gets killed, or refuses to start an activity | Overlay permission, `WifiLock`, battery exemption, `START_STICKY`, restart on boot and on opening the app |
| **Which addresses the device holds** | The wrong IP ends up in the notification | Interfaces are ranked: Wi-Fi, then wired, then others, with mobile and tunnels last |

`camera.capture` is unaffected by all of it. It drives the sensor through CameraX rather than asking
another app for a favour, which is why the assignment's rear-camera requirement is the *most*
portable part of the project rather than the least.

## Finding the right camera package

**There is no property for it.** Vendors ship plenty of camera-related properties —
`persist.vendor.camera.*` and similar — but they configure the camera HAL, not the app, and none of
them is a contract across manufacturers. `getprop` will tell you the manufacturer and the build
fingerprint; it will not tell you which package to launch.

**`PackageManager` intent resolution is the mechanism Android actually provides**, and it is what
the agent uses. One subtlety is worth knowing, because the obvious implementation gets it wrong:

`PackageManager.resolveActivity` looks like the right call and is not. On a device with **two**
camera apps and no user default — a Samsung with both the OEM camera and something sideloaded, say —
it returns `com.android.internal.app.ResolverActivity`, the system chooser. Launching that from a
headless agent puts a "which app?" dialog on a screen nobody is watching, and the command reports
success.

So the agent instead:

1. calls `queryIntentActivities` to get **every** handler,
2. picks one in `CameraAppChoice` — the registered default first, then a preinstalled app, then a
   stable alphabetical tie-break so repeated runs behave identically,
3. and launches it **by explicit component**, which no chooser can intercept.

`camremote camera-apps` shows the whole picture for any device:

```
camera.open would use: still_image_camera -> com.sec.android.app.camera/…AssistantActionActivity
still_image_camera (android.media.action.STILL_IMAGE_CAMERA): 1 handler(s)
    com.sec.android.app.camera/…AssistantActionActivity  [preinstalled, default handler]
app_camera_category (android.intent.action.MAIN): 0 handler(s)
image_capture (android.media.action.IMAGE_CAPTURE): 1 handler(s)
    com.sec.android.app.camera/com.sec.android.app.camera.Camera  [preinstalled, default handler]
```

"default handler" means the platform resolves the intent to it — which is the user's choice when
several apps compete, and simply "the only one" when one does. It does not imply anyone picked it.

A hard-coded table of OEM package names would be the other approach. It is worse: it needs updating
for every new device and every vendor rename, and it cannot see a camera app it has never heard of.
The `--package` parameter exists for the rare case where you want to override the choice by hand.

## The camera-app chain

`camera.open` tries these in order, stopping at the first that both resolves and starts:

| Strategy | Intent | Notes |
|---|---|---|
| `still_image_camera` | `android.media.action.STILL_IMAGE_CAMERA` | Means precisely "open the camera app". What ColorOS answers. |
| `app_camera_category` | `MAIN` + `android.intent.category.APP_CAMERA` | The category a launcher uses to find the camera. Widely declared — but **not** by One UI, which returns zero handlers for it. |
| `image_capture` | `android.media.action.IMAGE_CAPTURE` | Last: it opens the app in "take one and return it" mode, and started from a service there is nowhere to return to. |
| `launcher_entry` | `MAIN` + `LAUNCHER` | Only when `--package` names an app, as a last resort. |

The successful strategy comes back in the response, so a new device's behaviour is diagnosable
without picking it up:

```bash
camremote --json open-camera | grep strategy
```

**If you add a strategy**, add a matching `<intent>` to the `<queries>` block in
`AndroidManifest.xml`. Package visibility on API 30+ means an undeclared intent simply never
resolves — it fails quietly rather than loudly.

## Per-device notes

### Realme / OPPO (ColorOS) — verified on RMX3563, Android 14

Everything works. Three quirks worth knowing:

- **`adb shell pm grant` and `appops set` are refused**, even with USB debugging on. Permissions must
  come from the on-device dialogs `LaunchActivity` triggers. This is the single strongest argument
  for the app needing that one screen at all.
- **The overlay-permission deep link is ignored.** `ACTION_MANAGE_OVERLAY_PERMISSION` with a
  `package:` URI opens the full app list rather than this app's page; scroll to cam-remote.
- **Camera app:** `com.oplus.camera`, answers `still_image_camera`.
- ColorOS's battery manager is aggressive. Grant the battery exemption, and add cam-remote to
  *Settings → Battery → Background power consumption* if it still gets killed overnight.

### Samsung (One UI) — verified on SM-S921B (Galaxy S24), Android 14

Everything works. Three findings, two of them surprising:

- **`adb shell pm grant` and `appops set` are allowed**, unlike ColorOS. Granting permissions can be
  scripted end to end for development, which makes it much the fastest device to iterate on. (The
  shipped product never uses adb for this regardless — see `docs/DESIGN.md`.)
- **`MAIN` + `CATEGORY_APP_CAMERA` returns zero handlers.** One UI simply does not declare that
  category, so a project relying on it alone would report "no camera app" on a flagship Samsung.
  This is the clearest argument for the strategy chain existing at all.
- **`STILL_IMAGE_CAMERA` resolves to `AssistantActionActivity`**, not to the obvious `.Camera`:

  ```
  still_image_camera: com.sec.android.app.camera/…executor.AssistantActionActivity  [preinstalled]
  image_capture:      com.sec.android.app.camera/com.sec.android.app.camera.Camera  [preinstalled]
  ```

  That looks wrong and is not — it is a Bixby trampoline that forwards immediately to
  `com.sec.android.app.camera/.Camera`, which is what ends up on screen. Worth knowing before
  someone reads the component name in a log and files a bug.
- **Sleeping apps.** One UI's *Settings → Battery → Background usage limits* has "Sleeping apps" and
  "Deep sleeping apps" lists that will stop the agent regardless of Android's own rules. Add
  cam-remote to "Never sleeping apps"; the battery-optimisation exemption alone is not enough here.
- Knox-managed devices may block the overlay permission by policy. On those, `camera.capture`,
  `device.getprop` and `system.status` still work, and `camera.open` reports `PRECONDITION_FAILED`
  accurately.

### Pixel / stock Android

- **Camera app:** `com.google.android.GoogleCamera`. Closest to the documented behaviour, and the
  only family where `adb shell pm grant` is a usable shortcut during development, never for the
  shipped app itself.

### Xiaomi (MIUI/HyperOS)

- Requires "Autostart" to be enabled per-app, in addition to the battery exemption.
- MIUI has its own overlay permission separate from the AOSP one; both may need granting.

### Emulator (AOSP or Google APIs)

- **Give the AVD a rear camera**, or `camera.capture` correctly reports "this device reports no rear
  camera". In `~/.android/avd/<name>.avd/config.ini`:

  ```ini
  hw.camera.back=virtualscene
  ```

  `virtualscene` renders a synthetic room and produces genuine JPEGs; `emulated` gives a test
  pattern. Either satisfies the capture path.
- **Bare AOSP system images often ship no camera app at all.** `camera.open` then fails with
  `DEVICE_ERROR` naming every strategy it tried — which is the correct answer, not a bug.
- **Networking:** the emulator sits behind NAT on `10.0.2.x` and your host cannot reach it directly,
  so mDNS discovery will not find it. Use a port forward and `--host`:

  ```bash
  adb forward tcp:8099 tcp:8099
  camremote --host 127.0.0.1 status
  ```

  This is the one place adb is genuinely useful, and it is a property of emulator networking rather
  than of the agent.
- Google APIs images allow `adb shell pm grant`, which makes emulator setup much quicker than on a
  retail handset (again, a development convenience only — the shipped app grants nothing this way).

## A note on running the instrumented tests

`./gradlew :app:connectedDebugAndroidTest` reinstalls the app, which stops a running agent — so the
device goes quiet until the agent is started again. Opening cam-remote on the handset restarts it
(opening the app repairs an agent the system has killed), and so does a reboot. Pass
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep the APKs, and their granted
permissions, in place between runs.

## Diagnosing a new device

Start with one command, which gathers everything and keeps going even when part of the device is
broken:

```bash
camremote device-report --out matrix/samsung-s24.json
```

It reports the device and build, every permission that is missing, every camera app present and
which one `camera.open` would pick, and the command catalog. A section that fails — `camera.apps`
with the camera permission missing, say — is recorded as an error inside the report rather than
ending it, because a diagnostic that only runs on healthy devices is not much use.

Then, if something specific needs pinning down:

```bash
camremote discover              # if silent, the network blocks multicast -- use --host
camremote status                # names every missing permission
camremote camera-apps           # every camera app, and which one would be chosen
camremote --json open-camera    # 'strategy' says which intent the device answered
camremote take-picture          # independent of the camera app entirely
```

## Adding a device to the matrix

Worth recording, because it is the sort of thing that is expensive to rediscover:

Run `camremote device-report --out matrix/<device>.json` on each new handset and add a row:

| Device | Android | Camera package | Strategy that worked | Capture | Notes |
|---|---|---|---|---|---|
| realme RMX3563 (ColorOS) | 14 (API 34) | `com.oplus.camera` | `still_image_camera` | 2448×3264 | `pm grant` blocked; overlay deep link ignored |
| samsung SM-S921B (One UI) | 14 (API 34) | `com.sec.android.app.camera` | `still_image_camera` | 4080×3060 | `pm grant` allowed; `APP_CAMERA` category unhandled; Bixby trampoline activity |
