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
make avd         # create the API 35 AVD "test" if it does not exist
make emulator    # boot AVD "test" if needed
make test        # instrumented tests + PASS/FAIL report
make test-boot   # boot, wait for Android, and test
```

`make test` preserves Gradle's exit status and parses the connected-test XML
afterward through `scripts/test-report.sh`, producing one status line per test.

The AVD is not committed; `make avd` (and `make emulator`, `make test-boot`,
`make screenshots`) create it on demand via `scripts/ensure-avd.sh`
(`system-images;android-35;google_apis_playstore;x86_64`, device `pixel_6`) and
say so when they do. `scripts/emulator-up.sh` starts the emulator if needed and
`scripts/wait-for-boot.sh` waits (bounded) for boot; on failure the tail of
`/tmp/battwarn-emulator.log` is printed.

## Test coverage

The emulator suite currently contains 51 tests across these classes:

- `AboutActivityTest` — in-app legal screen renders the version, privacy
  statement, MIT license, third-party notices, and the full Apache-2.0 text.
- `AlertIntervalTest` — dismissal-based duration + quiet pause cycle.
- `BatteryDropTest` — below-highest-threshold detection and the active tier's
  notification title.
- `BootReceiverTest` — manifest contract and real boot/package-replace starts.
- `ChargingTest` — pause while charging, resume on unplug, opt-out behavior,
  active-tier-decides suppression across two thresholds, and the per-threshold
  "Warn only on battery" checkbox round-trip + load.
- `LockScreenAlertTest` — lock-screen gate, activity render/colors/auto-dismiss,
  UI round-trip.
- `MainActivityTest` — compact UI, live battery level, add/load threshold rows,
  per-row remove-sound behavior, per-row preview/sample text matching name+level,
  picked colors surviving a settings reopen, label/placeholder separation on
  empty fields, name/interval/duration round-trip through save, and save
  re-rendering the rows sorted by level descending.
- `OverlayAlertTest` — permission, render/hide, dismissal callback behavior.
- `OverrideSilentDndTest` — per-threshold silent/DND override gate, alarm
  attributes, UI round-trip.
- `ServiceSmokeTest` — foreground lifecycle and notification.
- `ThresholdConfigTest` — fresh baseline, JSON persistence round-trip, sorting,
  selection from the stored list.
- `ThresholdSelectionTest` — two-threshold end-to-end title flips and the pure
  selection helper.

The pure model logic (active-tier selection, JSON codec, color parsing) is also
covered by JVM unit tests in `app/src/test` (`AlertThresholdTest`, 8 tests) —
they run with `gradle testDebugUnitTest` and need no emulator. The unit tests use
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