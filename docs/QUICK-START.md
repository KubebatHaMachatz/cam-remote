# Quick start

Everything needed to take a photograph from another machine. Fuller detail lives in
[INSTRUCTIONS.md](INSTRUCTIONS.md); the reasoning behind any of it is in [DESIGN.md](DESIGN.md).

## 1. Install the app and open it once

```bash
cd android
./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. Get it onto the phone however
suits — email, cloud drive, USB — and tap it. Android will ask you to allow installing unknown apps
from whatever you used to transfer it; that is expected for a sideloaded build.

**Open the app once.** It draws nothing of its own — you will see only native Android dialogs, one
after another, and the app closes itself when they are done. Allow all four:

| Dialog | Grant it because |
|---|---|
| **Camera** | `take-picture` cannot work without it |
| **Notifications** | the notification is where the agent shows its address |
| **Appear on top** | Android will not let a background app open the camera app without it |
| **Stop optimising battery usage** | the agent stops answering with the screen off otherwise |

Declining any of them is fine — the rest still work, and you will be asked again the next time a
command needs one. Only the camera dialog is unavoidable for taking photographs.

The agent starts serving as soon as the app is opened, and restarts on boot.

**To stop it**, use the **Terminate service** button on that same notification. That is the only way
— there is no screen to switch it off from. Stopping is remembered, so it stays stopped through a
reboot; opening the app again starts it.

## 2. Point the client at it

Pull down the notification shade on the phone:

```
cam-remote is running
Accepting commands on 10.0.0.4:8099
```

That address is what every command needs. Pass it as `--host`, exactly as written:

```bash
./scripts/camremote --host 10.0.0.4:8099 status
```

The port may be left off — it defaults to 8099. Nothing is saved between commands, so `--host` goes
on every one.

Check the notification again if a command reports "connection refused". DHCP moves phones around
more than you would expect, and the agent watches for its own address changing and rewrites the
notification when it does — so what the phone shows is always current, even if what you typed
five minutes ago is not.

Python 3.11 or newer, and nothing to install. The phone and the control machine must be on the same
Wi-Fi network.

## 3. The commands

```bash
./scripts/camremote --host 10.0.0.4 <command>
```

| Command | What it does |
|---|---|
| **Primary** — what the agent is for | |
| `take-picture` | Takes a photograph with the **rear** camera and downloads it. `--out DIR` where to save it here, `--filename NAME`, `--path DIR` where to save it on the phone, under `Documents` |
| `open-camera` | Opens the phone's camera app. `--lens front\|rear` is a hint apps may ignore |
| `getprop KEY...` | Reads Android system properties, any number in one request |
| **Diagnostics** — how to inspect it | |
| `status` | The whole picture: permissions, camera apps, build properties, catalog. `--out FILE` writes it as JSON |
| `commands` | Returns the list of commands the agent supports, with descriptions |
| `camera-apps` | Every camera app on the device, and which one `open-camera` would use |

Those are the same two groups `camremote commands` prints, and the agent decides which is which.
Add `--json` to any of them for machine-readable output.

```bash
./scripts/camremote --host 10.0.0.4 take-picture --out ./shots
```

```
Captured 4080x3060, 3.83 MB in 2533 ms
On the device: Documents/cam-remote/camremote-20260830-184015-123.jpg
Saved to: shots/camremote-20260830-184015-123.jpg
```

## If something does not work

Run `status` first — it names every missing permission and what each one blocks.

Then watch what the phone thinks is happening:

```bash
adb logcat -s CamRemote
```

Every command is logged as it arrives and again with its result. If nothing appears there, the
request never reached the agent, and the address or the network is the problem rather than the app.
