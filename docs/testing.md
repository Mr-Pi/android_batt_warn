# Development and testing

## Nix shell

```sh
nix-shell
```

The shell supplies JDK 17, Gradle, Android SDK platform 35, build tools,
platform tools, NDK/CMake, an API 35 x86_64 system image, and the emulator.

## Commands

```sh
make build       # assembleDebug
make release     # unsigned release APK
make install     # build and install
make run         # install and launch
make clean       # Gradle clean
make devices     # adb devices
make emulator    # boot AVD "test" if needed
make test        # instrumented tests + PASS/FAIL report
make test-boot   # boot, wait for Android, and test
```

`make test` preserves Gradle's exit status and parses the connected-test XML
afterward through `scripts/test-report.sh`, producing one status line per test.

## Test coverage

The emulator suite currently contains 22 tests across these classes:

- `AlertIntervalTest` — dismissal-based duration + quiet pause cycle.
- `BatteryDropTest` — below-alert detection and warning notification title.
- `BootReceiverTest` — manifest contract and real boot/package-replace starts.
- `ChargingTest` — pause while charging, resume on unplug, opt-out behavior.
- `CriticalThresholdTest` — warning/critical split and threshold clamping.
- `MainActivityTest` — compact UI, live battery level, remove-sound behavior.
- `OverlayAlertTest` — permission, render/hide, dismissal callback behavior.
- `ServiceSmokeTest` — foreground lifecycle and notification.

## Emulator notes

- The API 35 AVD is hardware accelerated through KVM when available.
- The emulator often defaults to `AC powered: true`; battery tests explicitly
  set AC off before testing discharge behavior.
- Tests grant `POST_NOTIFICATIONS` and `SYSTEM_ALERT_WINDOW` through
  UiAutomation/appops where needed.
- Installing an updated APK can emit `MY_PACKAGE_REPLACED`, which intentionally
  starts the service. Reset battery state and force-stop after manual checks.
