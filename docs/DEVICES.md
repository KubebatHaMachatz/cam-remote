# Device notes

What varies between handsets, what the agent does about it, and what to check when a new device
misbehaves.

## What actually differs

The home-screen launcher is irrelevant here — nothing in this project goes through it. Three other
things vary, and all three are handled rather than assumed:

| Varies | Effect | How it is handled |
|---|---|---|
| **Which app answers the camera intent** | `camera.open` may find nothing | An ordered chain of three strategies, four when `--package` names an app; the response says which one worked |
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

`camremote --host <ip> camera-apps` shows the whole picture for any device:

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
camremote --host <ip> --json open-camera | grep strategy
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

Everything works, including the full no-UI permission flow described in `docs/DESIGN.md` §7 — a
fresh install, tap the icon, work through the native dialogs, then a command that hits a still-missing
permission successfully brings the operator back via the persistent notification. Several findings,
most of them surprising:

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
- **"Display over other apps" survives a full uninstall/reinstall.** Every ordinary runtime
  permission (camera, notifications) correctly resets to denied on a fresh install, as expected —
  but the overlay grant did not, across three separate uninstall/reinstall cycles while testing
  `LaunchActivity`'s on-demand prompt flow. One UI appears to key this particular grant to the
  package name (or the app's signing certificate) rather than to the installed app instance. Worth
  knowing if you are trying to reproduce the "overlay permission missing" precondition on this
  device for a demo: a plain reinstall will not get you there, and `system.status` is the way to
  confirm what is actually granted before assuming a fresh install means a fresh permission slate.
- **Sleeping apps.** One UI's *Settings → Battery → Background usage limits* has "Sleeping apps" and
  "Deep sleeping apps" lists that will stop the agent regardless of Android's own rules. Add
  cam-remote to "Never sleeping apps"; the battery-optimisation exemption alone is not enough here.
- Knox-managed devices may block the overlay permission by policy. On those, `camera.capture`,
  `device.getprop` and `system.status` still work, and `camera.open` reports `PRECONDITION_FAILED`
  accurately.
- **This is the handset that cost the project its discovery feature**, since removed from both
  sides. Registration was never the problem — the agent logged `Advertising cam-remote samsung
  SM-S921B on _camremote._tcp port 8099` and One UI announced the record — but the device then
  answered no `_camremote._tcp` query at all: 123 mDNS packets received in fifteen seconds, **none
  of them from the handset**. Neither a QU nor a plain QM query changed it, and neither did a
  multicast lock. See [below](#why-the-client-does-not-discover-the-agent) for the full account.

- **Testing the on-demand prompt via repeated `adb shell pm revoke`/`am force-stop` cycles is
  misleading.** After several rapid denials in a row, Android silently auto-resolves the next
  `requestPermissions()` call with no dialog at all — the standard anti-dialog-spam heuristic, not a
  bug in `PermissionPrompt`. A real user's single "Don't allow" tap does not trigger this; only
  scripted, repeated adb-driven denials do. If you need to reproduce the missing-permission path for
  a demo, prefer one genuine denial via the UI (or a clean reinstall) over cycling `pm revoke`.

### Pixel / stock Android

- **Camera app:** `com.google.android.GoogleCamera`. Closest to the documented behaviour, and the
  only family where `adb shell pm grant` is a usable shortcut during development, never for the
  shipped app itself.

### Xiaomi (MIUI/HyperOS)

- Requires "Autostart" to be enabled per-app, in addition to the battery exemption.
- MIUI has its own overlay permission separate from the AOSP one; both may need granting.

### Emulator — verified on an API 37 arm64 Google Play AVD

The whole suite passes here, including a real capture. Two things need setting up first.

#### Creating the AVD when there is no `avdmanager`

Android Studio installs the `emulator` binary and system images, but **not** `cmdline-tools` — so on
a fresh machine there is no `avdmanager`, and `emulator -list-avds` is empty with no obvious way to
change that. Installing `cmdline-tools` is one answer. Writing the two files `avdmanager` would have
written is quicker, and worth recording because the failure mode ("no AVDs, no tool to make one") is
an unhelpful place to start:

```bash
SDK=$HOME/Library/Android/sdk
AVD=$HOME/.android/avd/camremote_test.avd
mkdir -p "$AVD"

cat > $HOME/.android/avd/camremote_test.ini <<EOF
avd.ini.encoding=UTF-8
path=$AVD
path.rel=avd/camremote_test.avd
target=android-37
EOF

cat > "$AVD/config.ini" <<'EOF'
AvdId=camremote_test
avd.ini.displayname=camremote test
abi.type=arm64-v8a
tag.id=google_apis_playstore
image.sysdir.1=system-images/android-37.0/google_apis_playstore/arm64-v8a/
image.androidVersion.api=37
PlayStore.enabled=false
hw.cpu.arch=arm64
hw.ramSize=4096
disk.dataPartition.size=6442450944
hw.lcd.density=420
hw.lcd.width=1080
hw.lcd.height=2400
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.keyboard=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
EOF

$SDK/emulator/emulator -avd camremote_test -no-snapshot -no-boot-anim &
```

Match `image.sysdir.1` and `image.androidVersion.api` to whatever is actually in
`$SDK/system-images/`; the rest transfers unchanged. `minSdk` is 29, so anything from API 29 up will
do.

#### The rear camera

- **Give the AVD a rear camera**, or `camera.capture` correctly reports "this device reports no rear
  camera" — which is the command working, not failing. In `config.ini`:

  ```ini
  hw.camera.back=virtualscene
  ```

  `virtualscene` renders a synthetic room and produces genuine JPEGs; `emulated` gives a test
  pattern. Either satisfies the capture path. Note that `pm list features` shows
  `android.hardware.camera.front` and no matching `.back` entry even when the rear camera is
  present — `hasRearCamera()` asks Camera2 for `LENS_FACING_BACK` rather than trusting the feature
  list, and `camremote --host <ip> status` reporting `Rear camera: yes` is the check that means
  something.
- **Bare AOSP system images often ship no camera app at all.** `camera.open` then fails with
  `DEVICE_ERROR` naming every strategy it tried — which is the correct answer, not a bug.
- **A fresh AVD has no overlay or battery-optimisation grant**, and that turns out to be the most
  useful property it has. `camera.open` returns `PRECONDITION_FAILED` naming the missing overlay
  permission while `camera.capture` succeeds regardless, so the emulator is the easiest place to
  demonstrate that the capture path really is independent of the launch path — the claim the top of
  this document makes. On a fully set-up handset that distinction is invisible.
- **Networking:** the emulator sits behind NAT on `10.0.2.x` and your host cannot reach it directly,
  so it cannot be reached directly at all. Use a port forward and `--host`:

  ```bash
  adb forward tcp:8099 tcp:8099
  camremote --host 127.0.0.1 status
  ```

  This is the one place adb is genuinely useful, and it is a property of emulator networking rather
  than of the agent.
- Google APIs **and Google Play** images both allow `adb shell pm grant`, which makes emulator setup
  much quicker than on a retail handset (again, a development convenience only — the shipped app
  grants nothing this way). Grant `CAMERA` before running the instrumented suite, or the three
  capture tests `assumeTrue` their way to a pass without testing anything; check `skipped="0"` in
  `app/build/outputs/androidTest-results/connected/debug/*.xml` rather than trusting BUILD
  SUCCESSFUL.

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
camremote --host <ip> status --out matrix/samsung-s24.json
```

It reports the device and build, every permission that is missing, every camera app present and
which one `camera.open` would pick, and the command catalog. A section that fails — `camera.apps`
with the camera permission missing, say — is recorded as an error inside the report rather than
ending it, because a diagnostic that only runs on healthy devices is not much use.

Watch what the device itself thinks it is doing, which is the fastest way to tell "the command
never arrived" from "the command arrived and failed":

```bash
adb logcat -s cam-remote-app:CamRemote
```

Every command is logged as it arrives and again with its outcome, duration and payload — including
the path a capture was written to. A refusal is `WARN` and carries the same remediation the client
prints; `ERROR` means a defect in the agent and should never appear.

Every tag this app writes — this one included — starts with `cam-remote-app`, so
`adb logcat | grep cam-remote-app` is the broader net: everything the app itself logged, across
every class, with none of the framework or CameraX lines that also carry the app's PID.

Then, if something specific needs pinning down:

```bash
camremote --host <ip> status              # names every missing permission
camremote --host <ip> camera-apps         # every camera app, and which one would be chosen
camremote --host <ip> --json open-camera  # 'strategy' says which intent the device answered
camremote --host <ip> take-picture        # independent of the camera app entirely
```

## Why the client does not discover the agent

The control application once browsed for `_camremote._tcp` over mDNS and had a `pair` verb that
saved what it found. Both were removed, along with the whole browser, and `--host` is now required.
This is the evidence, because "we tried mDNS and it did not work" is worth rather less than the
measurements, and because anyone reviving the idea should start from them.

**Two of the three faults were ours, and both are easy to reintroduce.**

- **Listening only for a unicast reply is not enough.** Queries set the QU bit, and the client
  listened only on its own ephemeral port on the reasoning that this avoids contending for port 5353
  with the system responder. RFC 6762 §5.4 lets a responder answer a QU query by multicast anyway if
  it has not multicast that record recently, and Android's responder does exactly that: a 564-byte
  answer was captured going to `224.0.0.251:5353` while the client heard nothing.
- **Joining the multicast group on `INADDR_ANY` can receive nothing at all.** On macOS the kernel
  picks a default interface that need not be the one carrying mDNS. Measured on a Mac with one
  active Wi-Fi interface: an `INADDR_ANY` join received **0 packets in twelve seconds**, while a
  join pinned to that same interface received **63**.

**The third was the handset, and it is the one that ended the feature.** With both client bugs fixed
and the agent holding a multicast lock, the Galaxy S24 announced its record on registration and then
answered no `_camremote._tcp` query at all: 123 mDNS packets received in fifteen seconds, none of
them from the phone, while `--host` worked every time. Discovery therefore succeeded for a few
seconds after the app started and effectively never afterwards. The cause is most likely One UI
suppressing the responder for a backgrounded app — of a piece with the "Sleeping apps" behaviour
above — but that was never proven.

A client that usually cannot find the device is worse than one that always asks, especially when the
agent already displays its own `ip:port` in a notification. The agent's advertisement was removed
with the browser: a registration nothing consumes is not worth `NsdServiceAdvertiser`, a multicast
lock and two Wi-Fi permissions. The agent opens a port and serves commands on it.

### If you want to try it again

Both halves are in the git history and neither was large — the advertiser was thirty lines against
`NsdManager`. Should you revive them, these are the traps, in the order they were hit:

```bash
curl -s http://<device-ip>:8099/v1/health   # separates "not found" from "not running"
dns-sd -B _camremote._tcp local             # an independent second opinion on macOS
```

`dns-sd` answers from `mDNSResponder`'s cache, so a hit there does **not** prove the handset is
replying now — the single most misleading signal in this whole investigation, and the one that cost
an afternoon. To see what a device actually emits, restart the app and watch the announcement burst;
if the record appears then but no query is answered a minute later, that is the One UI behaviour
described above and no amount of client-side work will fix it.

## Adding a device to the matrix

Worth recording, because it is the sort of thing that is expensive to rediscover:

Run `camremote --host <ip> status --out matrix/<device>.json` on each new handset and add a row:

| Device | Android | Camera package | Strategy that worked | Capture | Notes |
|---|---|---|---|---|---|
| realme RMX3563 (ColorOS) | 14 (API 34) | `com.oplus.camera` | `still_image_camera` | 2448×3264 | `pm grant` blocked; overlay deep link ignored |
| samsung SM-S921B (One UI) | 14 (API 34) | `com.sec.android.app.camera` | `still_image_camera` | 4080×3060 | `pm grant` allowed; `APP_CAMERA` category unhandled; Bixby trampoline activity |
| emulator arm64 Google Play | 17 (API 37) | none installed | n/a — overlay ungranted | 1280×960 | AVD hand-authored; `virtualscene` rear camera; capture works while `camera.open` correctly refuses |
