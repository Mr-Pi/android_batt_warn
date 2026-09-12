# Batt Warn — Android battery monitor

Battery monitor for Android 9+ (minSdk 28, targetSdk 35). Runs as a
foreground service, polls the battery only while it is below a configurable
alert level, and shows a toast alert on a configurable interval while the
level stays below that threshold. Auto-starts on boot.

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
make emulator     # boot headless API 35 emulator (AVD "test") if not running
make test         # gradle connectedDebugAndroidTest on the running emulator
make test-boot    # boot emulator (waits for boot) then run instrumented tests
```

Direct Gradle options:

```
gradle assembleDebug            # build debug APK
gradle lintDebug                # Android Lint
gradle testUnitTest             # JVM unit tests (none currently)
gradle connectedDebugAndroidTest  # instrumented tests (emulator must be up)
```

Instrumented tests cover the autostart contract (BootReceiver resolves for
BOOT_COMPLETED / MY_PACKAGE_REPLACED and starts the FGS), the FGS lifecycle
(isRunning + startForeground notification; the runtime POST_NOTIFICATIONS
permission is granted via UiAutomation since API 33 blocks notification
posting without it), and the main activity UI.

## Project layout

```
app/src/main/kotlin/com/example/battwarn/
  MainActivity.kt              # live battery %, Start/Stop, settings form
  BatteryMonitorService.kt     # foreground service: IDLE/ACTIVE state machine, 25s-aware
  BootReceiver.kt              # auto-start on BOOT_COMPLETED / MY_PACKAGE_REPLACED
  AppSettings.kt               # config settings (SharedPreferences) + defaults
app/src/main/AndroidManifest.xml
app/src/main/res/              # layout, strings, colors, theme, icons
app/src/androidTest/kotlin/com/example/battwarn/
  BootReceiverTest.kt          # autostart contract (manifest + receiver behavior)
  ServiceSmokeTest.kt          # FGS lifecycle + notification
  MainActivityTest.kt          # main activity UI smoke test
shell.nix                      # nix dev env (JDK, Gradle, Android SDK, emulator)
Makefile                       # command wrappers
```

## Behavior

- **IDLE** (battery >= alert threshold): zero polling; waits on the system
  `ACTION_BATTERY_LOW` broadcast (~15%, device dependent).
- **ACTIVE** (below alert threshold): polls every `poll_interval_sec`
  (default 25s), shows a toast for `toast_duration_sec` (default 5s) each
  cycle. Exits when the level rises back above the alert threshold or on
  `ACTION_BATTERY_OKAY`.
- The foreground-service notification text is a fallback surface, since
  toasts are blocked in the background on Android 10+ and entirely on 12+.

## Configuration & conventions

- Settings live in `AppSettings` (SharedPreferences `battwarn_settings`):
  `alert_threshold`, `poll_interval_sec`, `toast_duration_sec`. Validation
  ranges: threshold 0-100, interval 5-3600s, duration 1-30s. The service
  reads prefs fresh each poll, so saved values apply immediately.
- `viewBinding` is enabled; use it in new layouts.
- Keep minSdk 28 / targetSdk 35 / compileSdk 35.
- Use Kotlin, short names, happy-path alignment, wrap errors with context.

## Before committing

1. `make build` — the debug APK must build.
2. `gradle lintDebug` — no new fatal lint issues.
3. If the app behavior changed, run `make test` (emulator + instrumented tests).