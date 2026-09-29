# Batt Warn — Android battery monitor

> **Hard rule — never commit without explicit instruction.**
> Do NOT run `git commit`, `git push`, `git rebase`, or otherwise mutate
> history unless the user explicitly asks you to. Prepare and verify work,
> leave changes uncommitted, and report the state. If you are unsure, ask.
> This rule is non-negotiable and applies to every session in this repo.

Battery monitor for Android 9+ (minSdk 28, targetSdk 35). Runs as a
foreground service, polls the battery only while it is below a user-configured
set of alert levels, and shows a colored alert banner/notification on a
configurable interval while the level stays below the active threshold.
Auto-starts on boot.

## Toolchain

Everything is provided by nix:

```
nix-shell          # enter the dev environment (regenerates local.properties)
```

The shell provides Gradle 8.14.4, JDK 17, the Android SDK (platform 35,
build-tools, platform-tools, NDK r27, CMake, the API 35 AVD system image,
and the emulator). On NixOS the shell also exports
`ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride` so AGP uses the
nix-built aapt2 instead of the non-NixOS-safe Maven binary.

## Commands

Run inside `nix-shell` (or via `make`, which wraps `nix-shell --run`):

```
make build        # gradle assembleDebug              -> app/build/outputs/apk/debug/app-debug.apk
make release      # gradle assembleRelease (unsigned)
make install      # build + adb install to connected device
make run          # install + start MainActivity
make clean        # gradle clean
make devices      # adb devices
make avd          # create the API 35 AVD "test" if it does not exist
make emulator     # boot headless API 35 emulator (AVD "test") if not running
make test         # gradle connectedDebugAndroidTest + per-test PASS/FAIL summary
make test-boot    # reboot emulator (boot it if needed) then run instrumented tests
make screenshots  # capture/refresh docs/screenshots from a running emulator
```

The AVD is not committed (it lives in `$ANDROID_AVD_HOME` or
`$HOME/.android/avd`). `make avd`, `make emulator`, `make test-boot` and
`make screenshots` create it on demand via `scripts/ensure-avd.sh`
(system image `system-images;android-35;google_apis_playstore;x86_64`, device
`pixel_6`), printing a notice when they do. `scripts/emulator-up.sh` starts the
emulator if needed and waits (bounded) for boot; the emulator log is
`/tmp/battwarn-emulator.log` and its tail is shown on any boot failure.
`make test-boot` reboots a reused emulator (`REBOOT=1`) so battery/SystemUI
state is clean, while `make test` runs against the emulator as-is.

Direct Gradle options:

```
gradle assembleDebug            # build debug APK
gradle lintDebug                # Android Lint
gradle testDebugUnitTest        # JVM unit tests (threshold model, no emulator)
gradle connectedDebugAndroidTest  # instrumented tests (emulator must be up)

make test streams failures live while running and ends with a per-test
PASS/FAIL summary parsed from the XML report (scripts/test-report.sh).
```

Instrumented tests cover the autostart contract (BootReceiver resolves for
BOOT_COMPLETED / MY_PACKAGE_REPLACED and starts the FGS), the FGS lifecycle
(isRunning + startForeground notification; the runtime POST_NOTIFICATIONS
permission is granted via UiAutomation since API 33 blocks notification
posting without it), threshold detection (battery dropped below the highest
threshold flips the notification to the active tier's name), the overlay
banner (SYSTEM_ALERT_WINDOW), multi-threshold selection (warning vs critical
tier name across the lower threshold, exactly-at-boundary stays on the higher
tier), the overlay dismiss callback (fires exactly once on auto-dismiss or
tap, never when the overlay cannot be shown), the per-threshold silent/DND
override (alarm-stream attributes + plays in silent/vibrate with the setting
on, blocked without), the lock-screen alert (default on; gate requires locked +
screen already on + overlay permission, activity renders the message/colors and
auto-dismisses), the charging suppression (the active tier's
warn-only-on-battery flag pauses or continues alerts while plugged in), the
threshold-list settings (fresh 20%/9% baseline, JSON
round-trip, add/load rows), the main activity UI, and the in-app legal screen
(version, privacy, MIT, third-party notices, Apache-2.0).

## Project layout

```
app/src/main/kotlin/com/example/battwarn/
  MainActivity.kt              # live battery %, Start/Stop, threshold-list settings
  BatteryMonitorService.kt     # foreground service: IDLE/ACTIVE state machine, active-tier selection
  BootReceiver.kt              # auto-start on BOOT_COMPLETED / MY_PACKAGE_REPLACED
  AppSettings.kt               # config settings (SharedPreferences) + defaults
  AlertThreshold.kt            # tier data class, JSON codec, selection helpers, color palette
  OverlayAlert.kt              # floating "always visible" alert banner (SYSTEM_ALERT_WINDOW)
  LockScreenAlertActivity.kt   # full-screen warning above the lock screen (showWhenLocked)
  AboutActivity.kt             # in-app legal screen (version, privacy, MIT, notices, Apache-2.0)
app/src/main/AndroidManifest.xml
app/src/main/res/              # layout, strings, colors, theme, icons
app/src/main/res/layout/activity_about.xml  # About screen layout
app/src/androidTest/kotlin/com/example/battwarn/
  BootReceiverTest.kt          # autostart contract (manifest + receiver behavior)
  ServiceSmokeTest.kt          # FGS lifecycle + notification
  MainActivityTest.kt          # main activity UI + add/load threshold rows + remove sound
  BatteryDropTest.kt           # threshold detection end-to-end
  ThresholdSelectionTest.kt    # multi-threshold warning/critical selection
  ThresholdConfigTest.kt       # threshold list baseline, JSON round-trip, sorting, selection
  OverlayAlertTest.kt          # overlay permission + banner render/hide/dismiss callback
  OverrideSilentDndTest.kt     # per-threshold silent/DND override + UI round-trip
  LockScreenAlertTest.kt       # lock-screen gate, activity render/colors/auto-dismiss, UI round-trip
  AboutActivityTest.kt         # in-app legal screen renders version, privacy, MIT, notices, Apache-2.0
app/src/test/kotlin/com/example/battwarn/
  AlertThresholdTest.kt        # JVM unit tests: selection, JSON codec, colors (no emulator)
LICENSE                        # MIT license text (single source for the in-app copy)
NOTICE                         # third-party attributions + AI disclosure
LICENSES/Apache-2.0.txt        # full Apache-2.0 text for the bundled libraries
shell.nix                      # nix dev env (JDK, Gradle, Android SDK, emulator)
Makefile                       # command wrappers
scripts/                       # test-report.sh, screenshots.sh, ensure-avd.sh,
                               # emulator-up.sh, wait-for-boot.sh
README.md                      # project overview and quick start
docs/                          # architecture, behavior, testing, screenshots
```

## Behavior

- **IDLE** (battery at or above every threshold): zero polling. Listens for
  the system `ACTION_BATTERY_CHANGED` broadcast (fires on every battery
  change) and becomes ACTIVE the moment the level drops below the highest
  threshold.
- **ACTIVE** (below at least one threshold) picks the active tier with
  `activeThreshold(level)`: the threshold with the smallest level that the
  level is strictly below (the most severe applicable tier). Only that tier's
  settings drive the alert.
- Each tier carries its own `interval_sec`, `duration_sec`, `sound_uri`,
  `lock_screen`, `override_silent_dnd`, and `bg_color`/`fg_color`:
  - The banner shows for `duration_sec` in the tier's colors; the **next
    banner shows `interval_sec` after the previous banner closed** (auto-
    dismiss or tap) — the interval is a quiet pause between alerts. Without
    overlay permission there is no banner, so the next cycle is scheduled
    interval seconds after the previous one started instead.
  - `sound_uri` (empty = silent) plays once per cycle; `override_silent_dnd`
    routes it to the alarm channel so it plays in silent/vibrate and typical
    DND.
  - With the active tier's `warn_only_on_battery` flag (default on), that
    tier's warnings pause while the device is plugged in (AC/USB/wireless) and
    resume on unplug — the notification keeps showing the level with the
    monitoring title meanwhile.
  - Exits when the level rises back above the highest threshold or on
    `ACTION_BATTERY_OKAY`.
- The alert is a floating banner (OverlayAlert) tinted with the tier's colors
  when the "Display over other apps" permission (SYSTEM_ALERT_WINDOW) is
  granted — visible even in the background. Without it, the notification is
  the fallback.
- With the tier's `lock_screen` flag (default on) and the overlay permission
  granted, a warning that fires while the device is locked **and the display
  is already on** is drawn full-screen over the lock screen by
  `LockScreenAlertActivity` (an activity with `showWhenLocked`); the overlay
  banner cannot show above the keyguard on Android 10+. The screen is never
  woken, so with the display off the notification stays the only surface.
  The `SYSTEM_ALERT_WINDOW` appop is what lets the foreground service start
  the activity from the background (`BAL_ALLOW_SAW_PERMISSION`); without it
  the alert falls back to the overlay/notification.
- The main screen shows the live battery level: the activity registers for
  `ACTION_BATTERY_CHANGED` while visible and re-renders on every change.
- The main screen is edge-to-edge (insets handled in `MainActivity`): status
  bar/nav bar icons switch to dark in light mode and stay white in dark mode
  so they stay readable on the app surface.
- The foreground-service notification is refreshed on every battery change
  (in both IDLE and ACTIVE) so it always shows the current level. Its title
  flips monitoring → the active tier's name as the level crosses thresholds.
  It doubles as the alert surface when the overlay permission isn't granted
  (toasts are unreliable from the background on Android 10+ and entirely on 12+).

## Configuration & conventions

- Settings live in `AppSettings` (SharedPreferences `battwarn_settings`):
  the `thresholds` JSON list (each entry: `level`, `name`, `intervalSec`,
  `durationSec`, `soundUri`, `lockScreen`, `overrideSilentDnd`, `bgColor`,
  `fgColor`, `warnOnlyOnBattery`). The fresh baseline is a 20% "Battery
  low" tier and a 9% "Battery critical" tier, both silent, lock-screen on,
  warn-only-on-battery on, red background/white text. Legacy single keys are
  ignored (no migration).
- Each threshold row (name field first, then level/interval/duration):
  level (0-100, unique), optional name (shown before the percentage and as
  the notification title; blank falls back to "Battery low"), interval/
  duration ≥ 1s with no upper cap, a picked tone (empty = silent; per-row
  Remove sound button), a "Show on lock screen" checkbox, a "Warn only on
  battery" checkbox, a "Play in silent / DND" checkbox (all three wrap onto
  as many lines as needed), and a color popup with preset background/text
  swatches shown as two side-by-side preview circles next to the sample
  banner. Save validates, stores sorted by level descending, reloads the
  rows in that sorted order, and restarts the running service so new values
  apply immediately.
- The tier model is in `AlertThreshold.kt` (pure Kotlin + org.json); the
  codec and selection helpers are JVM-unit-testable. MainActivity keeps the
  in-memory edits per row until Save.
- `viewBinding` is enabled; use it in new layouts.
- The in-app legal texts are generated at build time (`generateLegalResources`
  in `app/build.gradle.kts`) from the repo's `LICENSE`, `NOTICE`, and
  `LICENSES/Apache-2.0.txt`, which are the single source of truth. Never copy
  legal text into source or `res/`; edit the top-level files instead. When the
  bundled dependencies change, update `NOTICE` (and the README list) to match
  the release runtime classpath.
- Every source file (Kotlin, XML, Gradle, shell, make) carries the SPDX header
  `// SPDX-FileCopyrightText: 2026 Batt Warn authors` and
  `// SPDX-License-Identifier: MIT` (XML uses `<!-- ... -->` comments; keep it
  after the `<?xml ...?>` declaration). Keep the header on all new files.
- Keep minSdk 28 / targetSdk 35 / compileSdk 35.
- Use Kotlin, short names, happy-path alignment, wrap errors with context.

## Before committing

1. `make build` — the debug APK must build.
2. `gradle lintDebug` — no new fatal lint issues.
3. If the app behavior changed, run `make test` (emulator + instrumented tests).
