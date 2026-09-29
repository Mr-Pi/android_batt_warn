#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Make sure the emulator is booted: create the AVD if missing, start the
# emulator if it is not already running, then wait (bounded) for boot.
# Idempotent: a running emulator is reused.
#
# Overridable env:
#   AVD                   (default test)
#   BATTWARN_EMULATOR_LOG (default /tmp/battwarn-emulator.log)
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
avd=${AVD:-test}
log=${BATTWARN_EMULATOR_LOG:-/tmp/battwarn-emulator.log}

bash "$here/ensure-avd.sh"

# adb sees a booted emulator; pgrep catches one that is still launching (the
# process is qemu-system-x86_64-headless, not "emulator"). The [q] avoids
# matching this recipe's own command line.
if adb devices | grep -q "^emulator-" || pgrep -f -- "[q]emu-system.*-avd $avd" >/dev/null 2>&1; then
    echo "emulator already running"
else
    emulator -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
        -gpu swiftshader_indirect -memory 2048 -cores 4 >"$log" 2>&1 &
    echo "booting emulator $avd (log: $log)"
fi

BATTWARN_EMULATOR_LOG="$log" AVD="$avd" bash "$here/wait-for-boot.sh"
