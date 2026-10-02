#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Full test-environment teardown: uninstall the app and the instrumented test
# APK from the running emulator (best effort), stop the emulator, delete the
# AVD, and remove any leftover AVD image/data files from the AVD home.
# Idempotent: safe to run with no device, no emulator, or no AVD.
#
# Overridable env:
#   AVD  (default test)
#   PKG  (default com.example.battwarn)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
avd=${AVD:-test}
pkg=${PKG:-com.example.battwarn}

if adb devices 2>/dev/null | grep -q "^emulator-"; then
    echo "uninstalling $pkg.test and $pkg from the emulator"
    adb uninstall "$pkg.test" >/dev/null 2>&1 || true
    adb uninstall "$pkg" >/dev/null 2>&1 || true
fi

# Stops the emulator (bounded), then deletes the AVD via avdmanager.
bash "$here/remove-avd.sh"

# avdmanager only knows about AVDs its ini still lists; sweep the data/image
# directory too so a lost ini cannot strand gigabytes of emulator disk.
avd_home=${ANDROID_AVD_HOME:-$HOME/.android/avd}
rm -rf -- "$avd_home/$avd.avd" "$avd_home/$avd.ini"

echo "test environment cleaned: emulator stopped, apps removed, AVD '$avd' data deleted"
