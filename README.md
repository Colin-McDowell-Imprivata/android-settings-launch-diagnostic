# android-settings-launch-diagnostic

Minimal Android diagnostic app for investigating a Settings navigation bug where `startActivityForResult()` silently fails when the calling activity has `launchMode="singleInstance"`.

## Background

Apps that use `launchMode="singleInstance"` (such as kiosk apps and MDM clients) call `startActivityForResult()` to open Android Settings screens during permission setup. On certain Android 15 devices, the Settings screen does not appear when the calling activity has `launchMode="singleInstance"`. The call returns without error; the activity simply pauses and resumes immediately with nothing shown to the user.

See [FINDINGS.md](FINDINGS.md) for the full investigation write-up.

## Test Modes

The app has two modes. Both call `startActivityForResult()` but in different contexts.

**Mode 1 - Direct**  
A button tap immediately calls `startActivityForResult()`. This is a baseline for comparison. Neither MDA nor Locker actually works this way.

**Mode 2 - Dialog Flow**  
Replicates a common production call chain where the permission check is followed by an AlertDialog and the Settings launch is called from inside the dialog callback:

    onResume() -> permission check -> AlertDialog shown -> user taps Enable
                -> startActivityForResult() from inside the dialog callback

This is the pattern that exhibits the bug.

## How to Build and Install

1. Open in Android Studio (Hedgehog or later).
2. Connect a test device running Android 15 via USB with USB debugging enabled.
3. Build and run (`Run > Run 'app'` or `Shift+F10`).

The app does not require any special permissions or device admin enrollment.  
`launchMode="singleInstance"` is set in the manifest to reproduce the bug conditions.

## How to Use

- **Mode 1:** Tap any button in the blue section. Watch whether Settings opens.
- **Mode 2:** Check one or more permissions to simulate as missing, then tap "Start Dialog Flow". An AlertDialog will appear; tap "Enable" to trigger the `startActivityForResult()` call. If the bug is present, Settings will not open and the log will show a short pause-resume elapsed time (under 500ms).

The on-screen log shows timestamps and lifecycle events. Use the "Copy to Clipboard" button to capture it for filing a bug report.

**Logcat filter:**
```
adb logcat -v time SettingsDiagnostic:V ActivityTaskManager:V ActivityManager:V WindowManager:V *:S
```

## Interpreting Results

| Log entry | Meaning |
|---|---|
| `[BUG] Settings closed in only Nms` | Settings never opened; activity paused and resumed at once |
| `[OK] Settings was open for Nms` | Settings launched and stayed open as expected |
| `[WARNING] Cannot resolve intent` | The intent action does not resolve on this device/OS |
| `[ERROR] ActivityNotFoundException` | System rejected the intent at launch time |

The bug threshold is 500ms. Any pause-resume cycle shorter than that is flagged.

## Devices Tested

**Bug confirmed:**

| Field | Value |
|---|---|
| Manufacturer | Zebra Technologies |
| Model | ET401 |
| Android | 15 (API 35) |
| Build | 15-12-23.01-VG-U00-STD-ERS-04 |

**Not affected:**

- Samsung Galaxy (Android 15)
- Android emulator (Android 15)
