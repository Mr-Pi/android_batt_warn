# Batt Warn

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An Android battery monitor for Android 9+ that runs as a foreground service and
alerts before the battery gets too low.

<p align="center">
  <img src="docs/screenshots/main-light.png" width="270" alt="Batt Warn light-mode main screen">
  <img src="docs/screenshots/critical-banner.png" width="270" alt="Low-battery overlay banner">
  <img src="docs/screenshots/main-dark.png" width="270" alt="Batt Warn dark-mode main screen">
</p>

## Features

- Event-driven battery monitoring with no idle polling.
- Warning and critical thresholds with independent timing settings.
- Critical alerts can play a user-picked notification sound.
- Floating bottom banner when “Display over other apps” is enabled.
- Notification fallback with the current battery percentage.
- Optional **Warn only on battery** mode, which pauses alerts while charging.
- Starts after boot and package replacement.
- Compact light/dark UI with live battery updates.

More screenshots are available in [docs/screenshots](docs/screenshots/).

## Quick start

The complete development environment is provided by Nix:

```sh
nix-shell
make build       # app/build/outputs/apk/debug/app-debug.apk
make install     # build and install to a connected device
make run         # install and launch MainActivity
```

For emulator development:

```sh
make test-boot   # boot the API 35 AVD and run instrumented tests
make test        # run tests against an already-running emulator
```

`make test` streams Gradle failures and finishes with a per-test PASS/FAIL
summary. See [docs/testing.md](docs/testing.md) for the full workflow.

## Permissions

The app asks for or uses these Android permissions:

- `RECEIVE_BOOT_COMPLETED` — restart monitoring after boot or app replacement.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` — keep monitoring
  reliably in the background.
- `POST_NOTIFICATIONS` — show the foreground-service notification on Android 13+.
- `SYSTEM_ALERT_WINDOW` — show the optional banner above other apps.
- Battery-optimization exemption — requested from the Start flow to reduce Doze
  delays.

Android force-stop is a system-level exception: after a user force-stops an app,
Android suppresses its boot receiver until the app is opened again.

## Alert behavior

The app has two tiers:

- **Warning**: `critical_threshold <= level < alert_threshold`; uses warning
  duration and interval, without sound.
- **Critical**: `level < critical_threshold`; uses critical duration and
  interval, and plays the selected sound unless the ringer is silent/vibrate.

The interval is a quiet pause measured **after** the previous banner closes,
whether it auto-dismissed or was tapped. If the overlay permission is absent,
the notification remains available and the fallback cycle is scheduled from the
previous alert start.

Warnings pause while charging when **Warn only on battery** is enabled. The
foreground notification continues to show the current level while charging.

See [docs/behavior.md](docs/behavior.md) and [docs/architecture.md](docs/architecture.md)
for details.

## Battery usage

While above the alert threshold, the service does not poll, schedule alarms, or
hold a wake lock. It listens for `ACTION_BATTERY_CHANGED`. Active monitoring
only cycles while below the threshold; the app uses no network, GPS, or other
periodic background work.

## Project status

- Minimum SDK: 28 (Android 9)
- Target/compile SDK: 35
- Gradle: 8.14.4
- JDK: 17
- Instrumented tests: 22

## Documentation

- [Architecture](docs/architecture.md)
- [Behavior and settings](docs/behavior.md)
- [Testing and development](docs/testing.md)

## Third-party libraries

The app uses the following libraries (all declared in
[app/build.gradle.kts](app/build.gradle.kts)). Bundled runtime libraries are
shipped inside the APK; the rest are build/test-only.

Runtime (bundled in the APK):

- [androidx.core:core-ktx 1.15.0](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [androidx.appcompat:appcompat 1.7.0](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [com.google.android.material:material 1.12.0](https://github.com/material-components/material-components-android) — Apache-2.0
- [androidx.constraintlayout:constraintlayout 2.2.0](https://developer.android.com/jetpack/androidx) — Apache-2.0

Build/test-only:

- [junit:junit 4.13.2](https://github.com/junit-team/junit4) — EPL-1.0
- [androidx.test:runner 1.6.2](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [androidx.test:rules 1.6.1](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [androidx.test:core-ktx 1.6.1](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [androidx.test.ext:junit 1.2.1](https://developer.android.com/jetpack/androidx) — Apache-2.0
- [androidx.test.espresso:espresso-core 3.6.1](https://developer.android.com/jetpack/androidx) — Apache-2.0

See [NOTICE](NOTICE) for the full third-party notices with copyright lines and
the Apache-2.0 reference.

## License

MIT — see [LICENSE](LICENSE). The app ships the licenses inside it: the
**About** screen in the app shows the MIT license, third-party notices for the
bundled Apache-2.0 libraries, and the privacy statement. The in-app texts are
generated at build time from [LICENSE](LICENSE), [NOTICE](NOTICE), and
[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt), so each legal text is
maintained in exactly one place.

## AI assistance

This project was developed with the assistance of AI-based coding tools. See
[NOTICE](NOTICE) for the disclosure.
