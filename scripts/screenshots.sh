#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Capture/refresh docs/screenshots from a running API 35 emulator.
# Run via `make screenshots` so gradle/adb come from the nix-shell.
#
# The script reboots the emulator for a clean state (this AVD's SystemUI can
# wedge its notification shade after battery events), resets the app to the
# fresh 20%/9% baseline, drives the battery level and light/dark mode, and
# writes PNGs with `adb exec-out screencap`. Captures are best-effort: a
# missing one prints a warning and the rest run.
set -uo pipefail

PKG=com.example.battwarn
OUT=docs/screenshots
APK=app/build/outputs/apk/debug/app-debug.apk

mkdir -p "$OUT"

# --- device helpers ---------------------------------------------------------

adb wait-for-device
echo "== reboot for a clean SystemUI state =="
adb reboot
if ! bash scripts/wait-for-boot.sh; then
    echo "emulator did not boot; aborting" >&2
    exit 1
fi
sleep 10

CAP_FAILURES=0

cap() {
    local name="$1" attempt size tmp
    # Capture to a temp file so a run of blank frames cannot overwrite the
    # previous (good) committed image.
    tmp=$(mktemp)
    for attempt in 1 2 3 4; do
        adb exec-out screencap -p > "$tmp" 2>/dev/null || true
        size=$(stat -c%s "$tmp" 2>/dev/null || echo 0)
        # A blank/black frame compresses to ~16 KB; a real screen does not.
        if [ "$size" -gt 18000 ]; then
            mv "$tmp" "$OUT/$name.png"
            echo "  wrote $OUT/$name.png"
            return 0
        fi
        adb shell input keyevent 224 >/dev/null 2>&1 || true
        sleep 1
    done
    rm -f "$tmp"
    CAP_FAILURES=$(( CAP_FAILURES + 1 ))
    echo "  WARN: $name looks blank (${size:-0} bytes); keeping the previous image" >&2
}

set_battery() {
    adb shell dumpsys battery unplug >/dev/null 2>&1 || true
    adb shell dumpsys battery set ac 0 >/dev/null 2>&1 || true
    adb shell dumpsys battery set usb 0 >/dev/null 2>&1 || true
    adb shell dumpsys battery set wireless 0 >/dev/null 2>&1 || true
    adb shell dumpsys battery set level "$1" >/dev/null 2>&1 || true
}

reset_battery() { adb shell dumpsys battery reset >/dev/null 2>&1 || true; }

set_night() { adb shell cmd uimode night "$1" >/dev/null 2>&1 || true; }

open_main() {
    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
    sleep 1
}

screen_size() {
    adb shell wm size 2>/dev/null | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1
}

scroll_down() {
    local w h
    read -r w h <<< "$(screen_size)"
    [ -n "${w:-}" ] || { w=1080; h=2400; }
    adb shell input swipe "$(( w / 2 ))" "$(( h * 3 / 4 ))" "$(( w / 2 ))" "$(( h / 4 ))" 300
    sleep 1
}

scroll_top() {
    local w h i
    read -r w h <<< "$(screen_size)"
    [ -n "${w:-}" ] || { w=1080; h=2400; }
    for i in $(seq 1 8); do
        adb shell input swipe "$(( w / 2 ))" "$(( h / 4 ))" "$(( w / 2 ))" "$(( h * 3 / 4 ))" 200
    done
    sleep 1
}

close_shade() {
    adb shell cmd statusbar collapse >/dev/null 2>&1 || true
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    adb shell input keyevent 4 >/dev/null 2>&1 || true
    sleep 1
}

app_focused() {
    adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*$PKG"
}

# Bring the main activity to the front, dismissing the keyguard if needed.
ensure_app() {
    adb shell input keyevent 224 >/dev/null 2>&1 || true   # wake
    open_main
    if ! app_focused; then
        adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
        adb shell input keyevent 82 >/dev/null 2>&1 || true
        sleep 1
        open_main
    fi
}

# Tap the center of a view found by resource id via uiautomator (with retries).
tap_id() {
    local id="$1" attempt xml bounds x1 y1 x2 y2
    for attempt in 1 2 3; do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || { sleep 1; continue; }
        xml=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
        bounds=$(printf '%s' "$xml" \
            | grep -o "resource-id=\"$PKG:id/$id\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
            | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' \
            | head -1)
        if [ -n "$bounds" ]; then
            read -r x1 y1 x2 y2 <<< "$bounds"
            adb shell input tap "$(( (x1 + x2) / 2 ))" "$(( (y1 + y2) / 2 ))"
            return 0
        fi
        sleep 1
    done
    return 1
}

# Scroll down until the id is found, then tap it.
tap_id_scrolling() {
    local id="$1" i
    for i in $(seq 1 12); do
        tap_id "$id" && return 0
        scroll_down
    done
    return 1
}

service_running() {
    adb shell dumpsys activity services "$PKG" 2>/dev/null | grep -q "BatteryMonitorService"
}

# Text of a view found by resource id (empty when not found).
view_text() {
    local id="$1" xml
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    xml=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
    printf '%s' "$xml" | tr '<' '\n' | grep "id/$id" \
        | sed -n 's/.*text="\([^"]*\)".*/\1/p' | head -1
}

# Start monitoring, tapping the toggle only while it reads "Start monitoring".
start_monitoring() {
    local i
    for i in 1 2 3; do
        service_running && return 0
        [ "$(view_text monitor_button)" = "Start monitoring" ] || { sleep 1; continue; }
        tap_id monitor_button >/dev/null 2>&1 || true
        sleep 2
    done
    service_running || echo "  WARN: monitoring did not start" >&2
}

# The first row is the 20% tier unless the surface glitched and duplicated the
# last row (an intermittent artifact on this AVD); used to retry captures.
rows_ok() { [ "$(view_text name_field)" = "Battery low" ]; }

# Re-enter the activity so onResume refreshes the monitor controls from the
# live service state (the button can otherwise show a stale label).
refresh_controls() {
    adb shell input keyevent 3 >/dev/null 2>&1 || true   # HOME
    sleep 1
    open_main
    sleep 1
}

# --- setup ------------------------------------------------------------------

echo "== build + install =="
if ! gradle -q assembleDebug; then
    echo "build failed" >&2
    exit 1
fi
adb install -r "$APK" >/dev/null 2>&1 || adb install "$APK" >/dev/null

echo "== reset app to the fresh baseline =="
close_shade
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true
adb shell svc power stayon true >/dev/null 2>&1 || true
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
adb shell input keyevent 224 >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
sleep 1
adb shell pm clear "$PKG" >/dev/null 2>&1 || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

# --- main screen, light and dark -------------------------------------------

echo "== main screen =="
set_battery 100
set_night no
ensure_app
scroll_top
cap main-light

set_night yes
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
ensure_app
cap main-dark
set_night no

# --- active monitoring at a low level --------------------------------------
# 19% is below the app's 20% threshold but above Android's 15% low-battery
# warning, which crashes this AVD's SystemUI and corrupts the surface.
# Set the level before launching the app, then start monitoring: the service
# alerts immediately when started below a threshold.

echo "== low battery + banner =="
set_battery 19
sleep 2
close_shade
for attempt in 1 2 3 4; do
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    ensure_app
    start_monitoring
    refresh_controls
    # Re-trigger the first alert now that the controls read "Stop monitoring":
    # go idle above the threshold, then drop below it again.
    set_battery 100
    sleep 1
    set_battery 19
    sleep 1
    cap critical-banner
    sleep 7
    refresh_controls
    if rows_ok; then
        cap main-low
        break
    fi
    echo "  retry: rows not clean (attempt $attempt)" >&2
done

# --- About / legal screen ---------------------------------------------------

echo "== About screen =="
ensure_app
scroll_top
if tap_id_scrolling about_button; then
    sleep 1
    cap about
    adb shell input keyevent 4 >/dev/null 2>&1 || true
    sleep 1
else
    echo "  WARN: could not open the About screen" >&2
fi

# --- color picker -----------------------------------------------------------

echo "== color picker =="
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
ensure_app
if tap_id bg_color_swatch; then
    sleep 1
    cap color-picker
    adb shell input keyevent 4 >/dev/null 2>&1 || true
    sleep 1
else
    echo "  WARN: could not open the color picker" >&2
fi

# --- lock-screen alert ------------------------------------------------------

echo "== lock-screen alert =="
set_battery 100
ensure_app
start_monitoring
adb shell locksettings set-disabled false >/dev/null 2>&1 || true
adb shell input keyevent 26 >/dev/null 2>&1 || true   # screen off
sleep 1
adb shell input keyevent 26 >/dev/null 2>&1 || true   # screen on, locked
sleep 1
set_battery 19
sleep 2
cap lock-screen
# Unlock again and let the activity auto-dismiss.
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
sleep 1

# --- notification shade (last: can wedge SystemUI) --------------------------

echo "== notification =="
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
ensure_app
set_battery 19
start_monitoring
sleep 1
adb shell cmd statusbar expand-notifications >/dev/null 2>&1 || true
sleep 2
cap notification
close_shade

# --- teardown ---------------------------------------------------------------

echo "== reset state =="
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell locksettings set-disabled true >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb shell settings put system screen_off_timeout 60000 >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 0 >/dev/null 2>&1 || true
adb shell svc power stayon false >/dev/null 2>&1 || true
reset_battery
set_night no
if [ "$CAP_FAILURES" -gt 0 ]; then
    echo "done with $CAP_FAILURES failed capture(s): $OUT" >&2
    exit 1
fi
echo "done: $OUT"
