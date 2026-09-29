#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Wait for the emulator to finish booting before instrumented tests. Every wait
# is bounded, and on failure the tail of the emulator log is printed, so a
# missing or crashed emulator fails with an actionable message instead of
# hanging forever.
#
# Overridable env:
#   AVD                   (default test)  used to detect a crashed emulator
#   DEVICE_TIMEOUT_SEC    (default 120)   how long to wait for adb to see it
#   BOOT_TIMEOUT_SEC      (default 300)   how long to wait for boot_completed
#   SETTLE_TIMEOUT_SEC    (default 60)    how long to wait for bootanim
#   EMULATOR_GRACE_SEC    (default 20)    ignore "process gone" this long after start
#   BATTWARN_EMULATOR_LOG (default /tmp/battwarn-emulator.log)
set -euo pipefail

avd=${AVD:-test}
device_timeout=${DEVICE_TIMEOUT_SEC:-120}
boot_timeout=${BOOT_TIMEOUT_SEC:-300}
settle_timeout=${SETTLE_TIMEOUT_SEC:-60}
grace=${EMULATOR_GRACE_SEC:-20}
log=${BATTWARN_EMULATOR_LOG:-/tmp/battwarn-emulator.log}

dump_log() {
    if [ -f "$log" ]; then
        echo "--- last 30 lines of $log ---" >&2
        tail -n 30 "$log" >&2
        echo "-----------------------------" >&2
    fi
}

emulator_gone() {
    ! pgrep -f -- "[q]emu-system.*-avd $avd" >/dev/null 2>&1
}

fail() {
    echo "$1" >&2
    dump_log
    exit 1
}

# Poll adb in short slices so a crashed emulator is caught quickly.
waited=0
until timeout 5 adb wait-for-device 2>/dev/null; do
    if [ "$waited" -ge "$grace" ] && emulator_gone; then
        fail "emulator process is not running (AVD '$avd')"
    fi
    if [ "$waited" -ge "$device_timeout" ]; then
        fail "no emulator device after ${device_timeout}s"
    fi
    sleep 2
    waited=$((waited + 2))
done

waited=0
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ "$waited" -ge "$grace" ] && emulator_gone; then
        fail "emulator process disappeared before boot completed"
    fi
    if [ "$waited" -ge "$boot_timeout" ]; then
        fail "emulator did not finish booting within ${boot_timeout}s"
    fi
    sleep 2
    waited=$((waited + 2))
done

# Let the system settle (SystemUI, package manager) before running tests.
waited=0
until [ "$(adb shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')" = "stopped" ]; do
    [ "$waited" -ge "$settle_timeout" ] && break
    sleep 2
    waited=$((waited + 2))
done
