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
make emulator-clean # uninstall app + test APK, stop emulator, delete AVD data
make clean-all   # Gradle clean + emulator-clean
make devices     # adb devices
make avd         # create the API 35 AVD "test" if it does not exist
make emulator    # boot AVD "test" if needed
make test        # instrumented tests + PASS/FAIL report
make test-boot   # reboot (boot if needed), wait for Android, and test
make soak        # long-running randomized battery soak -> /tmp/battwarn-soak.log
```

`make test` preserves Gradle's exit status and parses the connected-test XML
afterward through `scripts/test-report.sh`, producing one status line per test.

The AVD is not committed; `make avd` (and `make emulator`, `make test-boot`,
`make screenshots`) create it on demand via `scripts/ensure-avd.sh`
(`system-images;android-35;google_apis_playstore;x86_64`, device `pixel_6`) and
say so when they do. `make avd-clean` stops the emulator and deletes it again
(`scripts/remove-avd.sh`; `make clean-all` runs it together with Gradle `clean`
through `emulator-clean`).
`make emulator-clean` (`scripts/clean-emulator.sh`) additionally uninstalls the
app and the instrumented test APK from a live emulator before stopping it, and
removes any leftover AVD image/data directory; `make uninstall` uninstalls both
`com.example.battwarn` and `com.example.battwarn.test`.
`scripts/emulator-up.sh` starts the emulator if needed and
`scripts/wait-for-boot.sh` waits (bounded) for boot; on failure the tail of
`/tmp/battwarn-emulator.log` is printed. `make test-boot` reboots a reused
emulator first (`REBOOT=1`) so battery/SystemUI state is clean; `make test`
runs against the emulator as-is.

`make soak` (`scripts/soak-test.sh`) is a separate long-running stability test
outside the Gradle suite: default 24 h (`MINUTES=` to configure). The script
needs a running device but never boots one itself — `make soak` starts the
emulator first.
A fresh `pm clear` baseline gives deterministic 20%/9% thresholds; monitoring
is started through the real UI (uiautomator bounds + `input tap`); then a
randomized profile runs (discharge −1% at random 1–5 min intervals, charge to
a random 40–100% target at a random 1–50% point). Every `CHECK_SEC` (60) the
harness asserts: FGS alive (with watchdog grace before a FAIL), pending
watchdog alarm, notification tier title matching the simulated level, and no
crash/ANR markers. All output — checks, events, and a live `BattWarn` logcat
stream — goes to one text file, by default `/tmp/battwarn-soak.log` (`LOG=`),
kept outside the repo for future debugging; FAIL additionally dumps battery,
service, and logcat context and the run continues. Ctrl-C resets the battery
simulation and prints a summary; the exit status is non-zero on any FAIL.

## Test coverage

The emulator suite currently contains 73 tests across these classes:

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
- `SnoozeTest` — per-threshold snooze: button present only with the flag and
  not while charging-suppressed; tapping "Snooze" freezes banners and flips
  the title/action; "Show alerts" resumes; crossing into the next tier
  releases the snooze; the checkbox round-trips through save and reload.
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