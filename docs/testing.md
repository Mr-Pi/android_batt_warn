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
make avd-clean   # stop the emulator and delete the AVD "test"
make clean-all   # Gradle clean + avd-clean
make devices     # adb devices
make avd         # create the API 35 AVD "test" if it does not exist
make emulator    # boot AVD "test" if needed
make test        # instrumented tests + PASS/FAIL report
make test-boot   # reboot (boot if needed), wait for Android, and test
```

`make test` preserves Gradle's exit status and parses the connected-test XML
afterward through `scripts/test-report.sh`, producing one status line per test.

The AVD is not committed; `make avd` (and `make emulator`, `make test-boot`,
`make screenshots`) create it on demand via `scripts/ensure-avd.sh`
(`system-images;android-35;google_apis_playstore;x86_64`, device `pixel_6`) and
say so when they do. `make avd-clean` stops the emulator and deletes it again
(`scripts/remove-avd.sh`; `make clean-all` also runs `clean`).
`scripts/emulator-up.sh` starts the emulator if needed and
`scripts/wait-for-boot.sh` waits (bounded) for boot; on failure the tail of
`/tmp/battwarn-emulator.log` is printed. `make test-boot` reboots a reused
emulator first (`REBOOT=1`) so battery/SystemUI state is clean; `make test`
runs against the emulator as-is.

## Test coverage

The emulator suite currently contains 67 tests across these classes:

- `AboutActivityTest` — in-app legal screen renders the version, privacy
  statement, MIT license, third-party notices, and the full Apache-2.0 text.
- `AlertIntervalTest` — dismissal-based duration + quiet pause cycle.
- `BatteryDropTest` — below-highest-threshold detection and the active tier's
  notification title.
- `BootReceiverTest` — manifest contract, real boot/package-replace starts, and
  the stopped-monitor gate (boot never resurrects a monitor the user stopped).
- `ChargingTest` — pause while charging, resume on unplug, opt-out behavior,
  active-tier-decides suppression across two thresholds, and the per-threshold
  "Warn only on battery" checkbox round-trip + load.
- `ImportExportTest` — the JSON dialog shows the stored config, Copy fills the
  clipboard, a valid paste loads the rows (and Save persists them), and an
  invalid paste errors without clobbering the rows.
- `LockScreenAlertTest` — lock-screen gate, activity render/colors/auto-dismiss,
  UI round-trip.
- `MainActivityTest` — compact UI, live battery level, add/load threshold rows,
  per-row remove-sound behavior, per-row preview/sample text matching name+level,
  picked colors surviving a settings reopen, label/placeholder separation on
  empty fields, name/interval/duration round-trip through save, and save
  re-rendering the rows sorted by level descending.
- `MonitorWatchdogTest` — the liveness watchdog restarts a dead service while
  monitoring is enabled, leaves a deliberately stopped one alone, does not
  restart a live one but re-posts its notification on a tick, the manifest
  declares the exact-alarm permissions, and the charging pause keeps re-arming
  its bounded re-check. A real watchdog broadcast re-arms the chain before it
  restarts the service; `schedule()` honors the monitoring flag; and simply
  launching MainActivity self-heals an enabled-but-dead monitor (and stays
  stopped after a deliberate Stop).
- `OverlayAlertTest` — permission, render/hide, dismissal callback behavior.
- `OverrideSilentDndTest` — per-threshold silent/DND override gate, alarm
  attributes, UI round-trip.
- `ServiceSmokeTest` — foreground lifecycle and notification; the notification's
  content intent opens MainActivity (sent in the background with the
  SYSTEM_ALERT_WINDOW appop granted, the exemption a real tap also relies on).
- `ThresholdConfigTest` — fresh baseline, JSON persistence round-trip, sorting,
  selection from the stored list.
- `ThresholdSelectionTest` — two-threshold end-to-end title flips and the pure
  selection helper.

The pure model logic (active-tier selection, JSON codec, color parsing) and the
watchdog restart decision are also covered by JVM unit tests in `app/src/test`
(`AlertThresholdTest`, `WatchdogDecisionTest`, 12 tests in total) —
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