#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT
#
# Long-running soak test (host-side, adb-only — no Gradle in the loop, so
# multi-hour runs are safe). Drives a randomized battery profile through the
# device: discharge -1% at random intervals (1-5 min by default), charge to a
# random target (40-100%), unplug, repeat. Every CHECK_SEC it verifies the
# monitor's invariants (FGS alive, watchdog alarm pending, notification title
# matches the tier expected for the simulated level, no crash/ANR markers) and
# appends everything — host checks plus a live device logcat stream — to one
# text file OUTSIDE the repo for later debugging. Any FAIL dumps context
# (battery, services, logcat tail) into the same log and the run continues.
#
# Intended to be run through `make soak` (which boots the emulator first).
# Ctrl-C ends the run gracefully: battery reset, summary, non-zero exit.
#
# Overridable env:
#   PKG                 (default com.example.battwarn)
#   APK                 (default app/build/outputs/apk/debug/app-debug.apk)
#   MINUTES             (default 1440 = 24 h)    total runtime
#   LOG                 (default /tmp/battwarn-soak.log) overwritten each run
#   CHECK_SEC           (default 60)             health-check interval
#   DISCHARGE_MIN_SEC   (default 60)  min pause between -1% discharges
#   DISCHARGE_MAX_SEC   (default 300) max pause between -1% discharges
#   CHARGE_STEP_SEC     (default 30)  pause between +1% charges
#   CHARGE_TARGET_LOW   (default 40)  min random charge stop point
#   CHARGE_TARGET_HIGH  (default 100) max random charge stop point
#   CHARGE_AT_LOW       (default 1)   min random charge start point
#   CHARGE_AT_HIGH      (default 50)  max random charge start point
#   WATCHDOG_GRACE_SEC  (default 960)  time to allow the watchdog to revive a
#                                       dead service (watchdog ticks every 15m)
#   ANDROID_SERIAL is honored by adb itself (multi-device).
set -uo pipefail

pkg=${PKG:-com.example.battwarn}
apk=${APK:-app/build/outputs/apk/debug/app-debug.apk}
minutes=${MINUTES:-1440}
log=${LOG:-/tmp/battwarn-soak.log}
check_sec=${CHECK_SEC:-60}
dis_min=${DISCHARGE_MIN_SEC:-60}
dis_max=${DISCHARGE_MAX_SEC:-300}
chg_step=${CHARGE_STEP_SEC:-30}
ct_low=${CHARGE_TARGET_LOW:-40}
ct_high=${CHARGE_TARGET_HIGH:-100}
ca_low=${CHARGE_AT_LOW:-1}
ca_high=${CHARGE_AT_HIGH:-50}
wd_grace=${WATCHDOG_GRACE_SEC:-960}

# Baseline default thresholds installed by a fresh `pm clear` (see AppSettings).
TIER_LOW_LEVEL=20
TIER_CRIT_LEVEL=9
TITLE_MONITORING="Battery monitor active"
TITLE_LOW="Battery low"
TITLE_CRIT="Battery critical"

check_n=0; fail_n=0; warn_n=0; restart_n=0; cycles=0
gone_since=0; title_misses=0; alarm_misses=0; offline_strikes=0
last_pid=""; sink_pid=""; interrupted=0; tracked=100; charging=0

iso() { date +%FT%T%z; }
emit() { printf '%s\n' "$*" >>"$log"; }
log() { local m; m="$(iso) $*"; emit "$m"; printf '%s\n' "$m"; }
warn() { warn_n=$((warn_n + 1)); log "WARN  $*"; }
fail() {
    fail_n=$((fail_n + 1))
    log "FAIL  $*"
    emit "---- context dump ----"
    adb shell dumpsys battery 2>&1 | sed 's/^/    /' >>"$log"
    emit "---- services ----"
    adb shell dumpsys activity services "$pkg" 2>&1 | head -n 60 | sed 's/^/    /' >>"$log"
    emit "---- last 150 logcat lines ----"
    adb logcat -d -t 150 2>&1 | sed 's/^/    /' >>"$log"
    emit "---- end context ----"
}
event() { log "EVENT $*"; }

rand_int() { echo $(( RANDOM % ($2 - $1 + 1) + $1 )); }
now() { date +%s; }

die() { log "SETUP-FAIL $*"; exit 2; }

adb_or_offline() {
    # Run adb with a bound; a non-zero result means the device is unresponsive.
    timeout 20 adb "$@" >/dev/null 2>&1
}

# --- logcat sink ------------------------------------------------------------

start_sink() {
    adb logcat -v epoch -s BattWarn:V ActivityManager:E AndroidRuntime:E System.err:W \
        >>"$log" 2>&1 &
    sink_pid=$!
}

# --- device setup -----------------------------------------------------------

setup() {
    if ! timeout 30 adb wait-for-device; then
        die "no adb device (boot the emulator first, e.g. via make soak)"
    fi
    if [ ! -f "$apk" ]; then
        command -v gradle >/dev/null || die "APK $apk missing and gradle unavailable — run make build"
        log "SETUP building debug APK"
        gradle assembleDebug >/dev/null || die "gradle assembleDebug failed"
    fi
    log "SETUP installing $apk"
    adb install -r "$apk" >/dev/null 2>&1 || die "adb install failed"

    # Deterministic baseline: default 20%/9% thresholds, monitoring off.
    log "SETUP clearing app data"
    adb shell pm clear "$pkg" >/dev/null || die "pm clear failed"
    adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
    adb shell dumpsys deviceidle whitelist "+$pkg" >/dev/null 2>&1 || true
    adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true

    log "SETUP battery profile start: ac=0 status=3 level=100"
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    adb shell dumpsys battery set ac 0 >/dev/null || die "dumpsys battery set failed"
    adb shell dumpsys battery set status 3 >/dev/null || die "dumpsys battery set failed"
    adb shell dumpsys battery set level 100 >/dev/null || die "dumpsys battery set failed"
}

tap_monitor_button() {
    # Press the real "Start monitoring" button through the UI (the only path
    # that sets monitoringEnabled + arms the watchdog). Bounds from uiautomator.
    adb shell am start -n "$pkg/.MainActivity" >/dev/null 2>&1 || die "am start failed"
    sleep 3
    local xml node bounds attempt x1 y1 x2 y2
    for attempt in 1 2 3; do
        bounds=""
        local i
        for i in 1 2 3; do
            adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || sleep 2
            xml=$(adb exec-out cat /sdcard/window_dump.xml 2>/dev/null)
            node=$(grep -oE "<node[^>]*resource-id=\"$pkg:id/monitor_button\"[^>]*>" <<<"$xml" | head -1)
            bounds=$(grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' <<<"$node" | head -1)
            [ -n "$bounds" ] && break
            sleep 2
        done
        [ -n "$bounds" ] || { event "monitor_button not found (tap attempt $attempt)"; continue; }
        # bounds="[x1,y1][x2,y2]" — no comma between the pairs; take digits in order.
        read -r x1 y1 x2 y2 <<<"$(grep -oE '[0-9]+' <<<"$bounds" | tr '\n' ' ')"
        adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) || continue
        local deadline=$(( $(now) + 15 ))
        while [ "$(now)" -lt "$deadline" ]; do
            if service_up; then
                event "monitoring started via UI tap (FGS up, attempt $attempt)"
                return 0
            fi
            sleep 2
        done
        event "service not up after tap attempt $attempt — retrying"
        sleep 2
    done
    die "service not running after 3 Start-tap attempts"
}

# --- device-state probes ----------------------------------------------------

service_up() {
    adb shell dumpsys activity services "$pkg" 2>/dev/null | grep -q BatteryMonitorService
}

alarm_pending() {
    # The watchdog's pending alarm shows as its walarm tag in dumpsys alarm.
    adb shell dumpsys alarm 2>/dev/null | grep -qF "*walarm*:$pkg.WATCHDOG"
}

notif_title() {
    # --noredact prints `android.title=String (…)` inside the record block.
    adb shell dumpsys notification --noredact 2>/dev/null | awk -v p="$pkg" '
        /NotificationRecord/ { insec = index($0, p) }
        insec && /android\.title=String \(/ {
            if (match($0, /android\.title=String \([^)]*\)/)) {
                print substr($0, RSTART + 22, RLENGTH - 23); exit
            }
        }' | tr -d '\r'
}

crash_scan() {
    # FATAL exceptions / ANRs for our package since the previous scan. The
    # crash buffer only holds fatal stacks; match package-owned lines there plus
    # explicit ANR / force-finish lines from ActivityManager.
    local since=$1 out
    out=$( { adb logcat -d -v epoch -b crash; adb logcat -d -v epoch -s ActivityManager:E; } 2>/dev/null |
        awk -v p="$pkg" -v s="$since" '$1 + 0 > s {
            if (index($0, "ANR in " p)) hit = 1
            else if (index($0, p) && index($0, "AndroidRuntime")) hit = 1
            else if (index($0, "Force finishing") && index($0, p)) hit = 1
        } hit { print; exit }' )
    [ -z "$out" ] || { printf '%s\n' "$out" | sed 's/^/    /' >>"$log"; return 1; }
    return 0
}

expected_title() {
    if [ "$charging" = 1 ]; then echo "$TITLE_MONITORING"
    elif [ "$tracked" -lt "$TIER_CRIT_LEVEL" ]; then echo "$TITLE_CRIT"
    elif [ "$tracked" -lt "$TIER_LOW_LEVEL" ]; then echo "$TITLE_LOW"
    else echo "$TITLE_MONITORING"; fi
}

health_check() {
    check_n=$((check_n + 1))
    local t now_= pid title expected

    now_=$(now)
    t=$(iso)

    if ! adb_or_offline shell true; then
        offline_strikes=$((offline_strikes + 1))
        fail "device offline (strike $offline_strikes) — waiting up to 60s to recover"
        if timeout 60 adb wait-for-device 2>/dev/null; then
            event "device back online"
            offline_strikes=0
        fi
        return
    fi
    offline_strikes=0

    # Background logcat sink must be alive (dies on device disconnect).
    if [ -n "$sink_pid" ] && ! kill -0 "$sink_pid" 2>/dev/null; then
        wait "$sink_pid" 2>/dev/null || true
        start_sink
        event "logcat sink restarted"
    fi

    # FGS alive (watchdog grace on a gap).
    if service_up; then
        if [ "$gone_since" -ne 0 ]; then
            restart_n=$((restart_n + 1))
            event "service revived after $((now_ - gone_since))s (restart #${restart_n})"
            gone_since=0
        fi
    elif [ "$gone_since" -eq 0 ]; then
        gone_since=$now_
        warn "FGS not running — waiting up to ${wd_grace}s for the watchdog to revive it"
    elif [ $((now_ - gone_since)) -gt "$wd_grace" ]; then
        fail "FGS gone for $((now_ - gone_since))s — watchdog did not revive monitoring"
    fi

    # Process identity (restart tracking is informational; the gap check above
    # owns pass/fail).
    pid=$(adb shell pidof "$pkg" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    if [ -n "$last_pid" ] && [ -n "$pid" ] && [ "$pid" != "$last_pid" ]; then
        event "process pid $last_pid -> $pid"
    fi
    [ -n "$pid" ] && last_pid=$pid

    # Watchdog alarm pending.
    if alarm_pending; then
        alarm_misses=0
    else
        alarm_misses=$((alarm_misses + 1))
        [ "$alarm_misses" -ge 3 ] && fail "watchdog alarm not pending for ${alarm_misses} checks"
    fi

    # Notification presence + tier title (two consecutive misses = FAIL, a
    # single one is a re-post race between the battery broadcast and the check).
    title=$(notif_title)
    expected=$(expected_title)
    if [ -z "$title" ]; then
        title_misses=$((title_misses + 1))
        if service_up && [ "$title_misses" -ge 2 ]; then
            fail "no $pkg notification for ${title_misses} consecutive checks while the service runs"
        fi
    elif [ "$title" != "$expected" ]; then
        title_misses=$((title_misses + 1))
        if [ "$title_misses" -ge 2 ]; then
            fail "notification title '$title' != expected '$expected' at level=${tracked}% ac=${charging} (2nd miss)"
        else
            warn "notification title '$title' != expected '$expected' (level=${tracked}% ac=${charging})"
        fi
    else
        title_misses=0
    fi

    # Crash/ANR markers since last scan.
    if ! crash_scan "$last_crash_scan"; then
        fail "crash/ANR markers found for $pkg since last scan (see excerpt above)"
    fi
    last_crash_scan=$now_

    emit "$t CHECK #${check_n} level=${tracked}% ac=${charging} title='${title:-none}' svc=$(service_up && echo up || echo down) pid=${pid:-none}"
}

# --- battery profile --------------------------------------------------------

set_level() { tracked=$1; adb shell dumpsys battery set level "$tracked"; event "battery -> ${tracked}% (ac=${charging})"; }
set_ac() {
    charging=$1
    # Explicit status too: `set ac 0` alone can leave status=2 (charging).
    if [ "$charging" = 1 ]; then
        adb shell dumpsys battery set ac 1 >/dev/null; adb shell dumpsys battery set status 2 >/dev/null
    else
        adb shell dumpsys battery set ac 0 >/dev/null; adb shell dumpsys battery set status 3 >/dev/null
    fi
    event "battery ac -> $charging"
}

next_charge_at() {
    # A strictly-lower level where charging starts.
    local hi=$ca_high
    [ "$((tracked - 1))" -lt "$hi" ] && hi=$((tracked - 1))
    [ "$hi" -lt "$ca_low" ] && hi=$ca_low
    rand_int "$ca_low" "$hi"
}

next_charge_target() {
    local t
    while :; do
        t=$(rand_int "$ct_low" "$ct_high")
        [ "$t" -gt "$tracked" ] && { echo "$t"; return; }
    done
}

run_loop() {
    local start end level_prev next_dis next_chg next_check charge_at charge_target nap s nxt
    start=$(now); end=$((start + minutes * 60))
    last_crash_scan=$start
    tracked=100; charging=0
    charge_at=$(next_charge_at)
    charge_target=0
    next_dis=$((start + $(rand_int "$dis_min" "$dis_max")))
    next_chg=0
    next_check=$((start + 5))
    log "SOAK START minutes=$minutes check=${check_sec}s discharge=-1%/${dis_min}-${dis_max}s charge=+1%/${chg_step}s targets=${ct_low}-${ct_high} charge-at=${ca_low}-${ca_high} log=$log"
    event "profile: charge when <= ${charge_at}%"

    while :; do
        [ "$interrupted" = 1 ] && { log "SOAK interrupted (Ctrl-C)"; break; }
        s=$(now)
        [ "$s" -ge "$end" ] && { log "SOAK runtime elapsed"; break; }

        if [ "$charging" = 0 ] && [ "$s" -ge "$next_dis" ]; then
            if [ "$tracked" -gt 0 ]; then
                set_level $((tracked - 1))
                next_dis=$((s + $(rand_int "$dis_min" "$dis_max")))
            fi
            if [ "$tracked" -le "$charge_at" ]; then
                set_ac 1
                charge_target=$(next_charge_target)
                next_chg=$((s + chg_step))
                event "charging to target ${charge_target}%"
            fi
        elif [ "$charging" = 1 ] && [ "$s" -ge "$next_chg" ]; then
            if [ "$tracked" -lt "$charge_target" ]; then
                set_level $((tracked + 1))
                next_chg=$((s + chg_step))
            else
                set_ac 0
                cycles=$((cycles + 1))
                charge_at=$(next_charge_at)
                next_dis=$((s + $(rand_int "$dis_min" "$dis_max")))
                event "unplugged after $cycles full cycle(s); next charge point <= ${charge_at}%"
            fi
        fi

        if [ "$s" -ge "$next_check" ]; then
            health_check
            next_check=$((s + check_sec))
        fi

        # Sleep until the next scheduled event in 1s foreground slices: a
        # trapped INT/TERM runs after the current command, so `wait`ing on a
        # long background sleep would defer Ctrl-C until that sleep ends.
        nxt=$end
        for t in "$next_dis" "$next_chg" "$next_check"; do
            [ "$t" -ne 0 ] && [ "$t" -lt "$nxt" ] && nxt=$t
        done
        nap=$((nxt - $(now))); [ "$nap" -lt 1 ] && nap=1; [ "$nap" -gt "$check_sec" ] && nap=$check_sec
        local slept=0
        while [ "$slept" -lt "$nap" ] && [ "$interrupted" = 0 ]; do
            sleep 1
            slept=$((slept + 1))
        done
    done
}

finish() {
    [ -n "$sink_pid" ] && { kill "$sink_pid" 2>/dev/null; wait "$sink_pid" 2>/dev/null; } || true
    adb shell dumpsys battery reset >/dev/null 2>&1 || true
    event "battery simulation reset (monitoring left running)"
    log "SOAK SUMMARY checks=${check_n} fails=${fail_n} warns=${warn_n} restarts=${restart_n} cycles=${cycles} final=${tracked}% ac=${charging} log=${log}"
    [ "$fail_n" -gt 0 ] && exit 1
    exit 0
}

trap 'interrupted=1' INT TERM
trap 'finish' EXIT

: >"$log" || { echo "cannot write $log" >&2; exit 2; }
log "==== battwarn soak ===="
log "device: $(adb get-serialno 2>/dev/null | tr -d '\r') android=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
setup
tap_monitor_button
start_sink
run_loop
