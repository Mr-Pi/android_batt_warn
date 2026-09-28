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

The emulator suite currently contains 43 tests across these classes:

- `AlertIntervalTest` — dismissal-based duration + quiet pause cycle.
- `BatteryDropTest` — below-highest-threshold detection and the active tier's
  notification title.
- `BootReceiverTest` — manifest contract and real boot/package-replace starts.
- `ChargingTest` — pause while charging, resume on unplug, opt-out behavior.
- `LockScreenAlertTest` — lock-screen gate, activity render/colors/auto-dismiss,
  UI round-trip.
- `MainActivityTest` — compact UI, live battery level, add/load threshold rows,
  per-row remove-sound behavior.
- `OverlayAlertTest` — permission, render/hide, dismissal callback behavior.
- `OverrideSilentDndTest` — per-threshold silent/DND override gate, alarm
  attributes, UI round-trip.
- `ServiceSmokeTest` — foreground lifecycle and notification.
- `ThresholdConfigTest` — fresh baseline, JSON persistence round-trip, sorting,
  selection from the stored list.
- `ThresholdSelectionTest` — two-threshold end-to-end title flips and the pure
  selection helper.

The pure model logic (active-tier selection, JSON codec, color parsing) is also
covered by JVM unit tests in `app/src/test` (`AlertThresholdTest`, 7 tests) —
they run with `gradle testUnitTest` and need no emulator. The unit tests use
the `org.json` artifact because the platform's JSON classes are not available on
the plain JVM.

## Emulator notes

- The API 35 AVD is hardware accelerated through KVM when available.
- The emulator often defaults to `AC powered: true`; battery tests explicitly
  set AC off before testing discharge behavior.
- Tests grant `POST_NOTIFICATIONS` and `SYSTEM_ALERT_WINDOW` through
  UiAutomation/appops where needed.
- Installing an updated APK can emit `MY_PACKAGE_REPLACED`, which intentionally
  starts the service. Reset battery state and force-stop after manual checks.
- Threshold tests clear `battwarn_settings` in teardown so the fresh 20%/9%
  baseline applies between classes.