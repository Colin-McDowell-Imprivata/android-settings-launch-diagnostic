# Android Settings Launch Diagnostic

A minimal Android diagnostic app for investigating a bug where `startActivityForResult()` silently fails to open a Settings screen when the calling activity has `android:launchMode="singleInstance"`.

## Background

Apps that use `launchMode="singleInstance"` (such as kiosk apps and MDM clients) call `startActivityForResult()` to open Android Settings screens during permission setup. On certain devices, the Settings screen does not appear — the call returns without error and the activity simply pauses and resumes immediately, as if nothing happened.

This bug was first identified and confirmed on a **Zebra ET401 running Android 15**, and this tool was developed and tested on that device. The same behavior has since been reported on other devices as well, though those have not yet been independently confirmed. The tool is device-agnostic and can be used to test the bug and evaluate workarounds on any Android device.

## Test Modes

**Mode 1 — Direct Launch**  
A button tap immediately performs the launch using the selected API method. Use this as a baseline.

**Mode 2 — Dialog Flow**  
Mirrors a first-time permissions setup flow: a confirmation dialog appears first, and the launch is performed from inside the dialog callback. Select one or more Settings screens to include in the flow, then tap "Start Dialog Flow".

## Launch Options

At the top of the screen, two groups of toggles control how the launch is performed.

**API Method** (select one — mutually exclusive):

| Option | What it does |
|---|---|
| `startActivityForResult()` | The default Android API for launching an activity and receiving a result when it closes. This is the method that triggers the bug on affected devices. Selected by default. |
| `registerForActivityResult()` | The modern AndroidX replacement for `startActivityForResult()`. Uses a different code path but converges at the same framework layer. |
| `startActivity()` (no result) | Launches the Settings screen without requesting a result. |
| Trampoline activity | Launches a lightweight transparent internal activity into a normal (non-`singleInstance`) task, which then calls `startActivityForResult()` on behalf of the main activity. Tests whether the bug is specific to `singleInstance` callers. |

**Modifiers** (independent — can be combined with any API method above):

| Option | What it does |
|---|---|
| `FLAG_ACTIVITY_NEW_TASK` | Adds this flag to the Settings intent, forcing it to launch into its own independent task rather than being associated with the calling task. |
| `Handler.post()` | Wraps the launch in a `Handler.post()` call, deferring it by one message-loop frame so it fires after the current call stack (e.g. a dialog callback) has fully unwound. |

## How to Build and Install

**Option A — Install the APK directly (easiest)**  
Download the latest APK from the [Releases](../../releases) section of this repository. Enable USB debugging on the device and install via adb, replacing the filename with the one you downloaded:
```
adb install /path/to/settings-launch-diagnostic-vX.X.apk
```

**Option B — Build from source**  
1. Open the project in a recent version of Android Studio (must support AGP 8.x).
2. Connect a test device via USB with USB debugging enabled.
3. Build and run the app.

The app supports Android 7.0 (API 24) and above. No special permissions or device admin enrollment are required. `launchMode="singleInstance"` is set in the manifest to reproduce the bug conditions.

## How to Use

1. Select the API method and any modifiers you want to test.
2. **Mode 1:** Tap any button and watch whether the Settings screen opens.
3. **Mode 2:** Check one or more permissions, tap "Start Dialog Flow", then tap "Enable" on each dialog that appears.

The on-screen log shows timestamps and lifecycle events. Use "Copy to Clipboard" to capture the log for a bug report. All events are also written to Logcat under the tag `SettingsDiagnostic`.

Logcat filters — app events only (mirrors the in-app log), useful for capturing to a file:
```
adb logcat -v time SettingsDiagnostic:V *:S
```

With system-side framework events (intent routing, task and window transitions) — use this to pinpoint where the framework handles or drops the launch:
```
adb logcat -v time SettingsDiagnostic:V ActivityTaskManager:V ActivityManager:V WindowManager:V *:S
```

## Interpreting Results

| Log entry | Meaning |
|---|---|
| `[BUG] Settings closed in only Nms` | Settings never opened; activity paused and resumed immediately |
| `[OK] Settings was open for Nms` | Settings launched and stayed open as expected |
| `[WARNING] Cannot resolve intent` | The intent action does not resolve on this device/OS |
| `[ERROR] ActivityNotFoundException` | System rejected the intent at launch time |

The bug threshold is 500ms. Any pause-resume cycle shorter than that is flagged.
