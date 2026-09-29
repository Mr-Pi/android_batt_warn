#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Create the test AVD if it does not exist. Idempotent: safe to run every time.
#
# The AVD lives outside the repo (in $ANDROID_AVD_HOME or $HOME/.android/avd),
# so it is created on demand rather than committed. The system image and device
# profile come from shell.nix (includeSystemImages, google_apis_playstore,
# x86_64, platform 35).
#
# Overridable env:
#   AVD     (default test)
#   SYS_IMG (default system-images;android-35;google_apis_playstore;x86_64)
#   DEVICE  (default pixel_6)
set -euo pipefail

avd=${AVD:-test}
sys_img=${SYS_IMG:-system-images;android-35;google_apis_playstore;x86_64}
device=${DEVICE:-pixel_6}

if emulator -list-avds 2>/dev/null | grep -qx "$avd"; then
    exit 0
fi

echo "AVD '$avd' not found — creating it now" >&2
echo "  image:  $sys_img" >&2
echo "  device: $device" >&2
echo no | avdmanager create avd -n "$avd" -k "$sys_img" -d "$device" --force
echo "AVD '$avd' created." >&2
