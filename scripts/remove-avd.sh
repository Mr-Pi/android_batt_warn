#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Stop the emulator and delete the test AVD, undoing ensure-avd.sh.
# Idempotent: safe to run when the emulator is stopped or the AVD is gone.
#
# Overridable env:
#   AVD                   (default test)
#   EMULATOR_LOG_WAIT_SEC (default 30) how long to wait for the emulator to die
set -euo pipefail

avd=${AVD:-test}
kill_timeout=${EMULATOR_LOG_WAIT_SEC:-30}

emulator_running() {
    adb devices 2>/dev/null | grep -q "^emulator-" ||
        pgrep -f -- "[q]emu-system.*-avd $avd" >/dev/null 2>&1
}

if emulator_running; then
    echo "stopping the emulator"
    adb emu kill >/dev/null 2>&1 || true
    waited=0
    while emulator_running; do
        if [ "$waited" -ge "$kill_timeout" ]; then
            echo "emulator did not stop; killing the process" >&2
            pkill -f -- "[q]emu-system.*-avd $avd" >/dev/null 2>&1 || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
fi

if emulator -list-avds 2>/dev/null | grep -qx "$avd"; then
    echo "deleting AVD '$avd'" >&2
    avdmanager delete avd -n "$avd" >/dev/null
    echo "AVD '$avd' deleted." >&2
else
    echo "AVD '$avd' does not exist" >&2
fi
