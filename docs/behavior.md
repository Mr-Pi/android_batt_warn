# Behavior and settings

## Threshold list

Monitoring is driven by a user-configurable list of thresholds. Each threshold
alerts below its level and carries its own:

- **Name** (optional) — shown before the percentage in the banner and as the
  foreground-notification title. Unnamed thresholds fall back to "Battery low".
- **Interval** — the quiet pause after the banner closes.
- **Duration** — how long the banner stays up.
- **Sound** — a picked notification tone; empty means silent.
- **Show on lock screen** — whether this tier may draw full-screen over the
  keyguard.
- **Play in silent / DND** — whether this tier's sound overrides the silent
  ringer and typical Do Not Disturb.
- **Background / text colors** — the banner and lock-screen alert surface.

## Tier selection

The active tier is the threshold with the **smallest level still above the
current battery percentage** — the most severe applicable tier. With thresholds
at 20% and 9%:

- `level >= 20` → idle (no alerts, monitoring title).
- `9 <= level < 20` → the 20% tier applies.
- `level < 9` → the 9% tier applies.

Only the active tier's settings drive the alert cycle at any moment.

## Scheduling

Every tier uses the same scheduling rule: the configured interval is a quiet
pause **after** the banner closes. Tapping the banner closes it early and starts
that pause immediately.

A missed wake-up (a dismissal that never arrives, a battery broadcast that is
throttled) must not dead-end the cycle. While the level is below a threshold the
service always keeps a next poll pending: if none is queued it arms one, and the
doze backstop alarm guarantees the cycle advances even with the CPU asleep.
Turning the screen on below a threshold, or unplugging while below one, polls
immediately rather than waiting out the quiet pause.

## Charging

Each threshold carries its own **Warn only on battery** flag (default on). The
**active** tier's flag decides: while plugged into AC, USB, or wireless power,
the active tier's banners/sounds stop when its flag is on. The notification
still shows the live battery level and uses the monitoring title. Unplugging
while still below a threshold resumes the first alert immediately.

Because only the active tier governs, a threshold with the flag off keeps
warning while charging — for example, a 60% tier with the flag off warns from
60% down to 50%, while a 50% tier with the flag on silences the alerts below
50% while charging. During the pause the service keeps a slow self-armed
re-check running (15 minutes), so even a lost unplug broadcast resumes the
alerts instead of ending them.

## Alert sound

Use **Pick** on a threshold to open Android's notification ringtone picker.
**Remove** or choosing **Silent** stores an empty sound URI and disables
playback. The service also respects Android's silent/vibrate ringer state.

**Play in silent / DND** (off by default per threshold) makes that tier's alert
sound play even when the ringer is silent, on vibrate, or the device is in Do
Not Disturb. It routes the tone to the alarm stream, which Android does not mute
with a silent ringer and exempts from typical DND. The one case it cannot pierce
is a DND rule configured to silence alarms as well.

## Import / export

**Import / export settings** (above About) opens a dialog with the saved
configuration as JSON — the same array stored under the `thresholds` key, or the
fresh 20%/9% baseline when nothing has been saved yet. **Copy** puts it on the
clipboard. Paste a config back and press **Import** to load it into the rows;
**Save** then persists it, exactly like hand-edited rows. An invalid paste stays
in the dialog with an error and leaves the rows untouched. Unsaved row edits are
not part of the exported JSON.

## Colors

Each threshold's background and text colors are chosen from a preset palette
shown in a small popup; two side-by-side preview circles in the row always
show the currently selected pair. The colors tint the bottom banner (the
rounded background and the message text) and the full-screen lock-screen
alert. The foreground notification itself is not colored, and the main
screen's battery number keeps its green/red rule based on the highest
threshold.

## Overlay and notification fallback

With the “Display over other apps” permission, the service displays a colored
bottom banner above other apps. It is non-focusable, auto-dismisses, and can be
tapped to close. Without that special permission, the foreground notification
remains the reliable alert surface.

Some apps using `FLAG_SECURE` may block overlays; the notification remains
available in that case.

## Lock-screen alert

The per-threshold **Show on lock screen** flag defaults on. When an alert fires
while the device is locked **and the display is already on**, the warning is
drawn full-screen over the lock screen instead of the bottom banner. Android
keeps `TYPE_APPLICATION_OVERLAY` windows below the keyguard, so the lock-screen
surface is a `showWhenLocked` activity rather than the usual banner.

The alert is never allowed to wake the screen: if the display is off, nothing
is shown over the lock screen and the notification remains the surface. The
activity stays up for the tier duration, can be tapped to dismiss, and the
quiet interval is then measured from that dismissal, exactly like the banner.

Showing over the lock screen requires the “Display over other apps”
permission: the `SYSTEM_ALERT_WINDOW` appop is what lets the foreground service
start the full-screen activity from the background. Without it the alert falls
back to the overlay/notification behavior above.

## Autostart and reliability

The boot receiver handles device boot and package replacement — but only when
the user still wants monitoring (a deliberate **Stop** is never undone by a
reboot). The service returns `START_STICKY` and requests a battery-optimization
exemption from the Start flow.

A foreground service can still be killed by the system (doze, memory pressure,
an aggressive OEM battery manager), and a sticky restart is not dependable: on
Android 12+ a restarted foreground service can be refused `startForeground()`
and die again. A liveness watchdog covers that gap **automatically, with no
user interaction**:

- An `AlarmManager` exact alarm (`SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` —
  the latter is auto-granted on Android 13+, so recovery works out of the box
  even where Android 14+ no longer grants the exact-alarm right by default)
  fires about every 15 minutes while monitoring is enabled.
- Each tick: if the service died, it is restarted. Holding a pending exact
  alarm is an Android 12+ exemption from the background
  foreground-service-start restriction, so the restart is permitted. If the
  service is alive, the tick sends a refresh: the notification is re-posted
  from the sticky battery (worst-case staleness one tick, even if every
  broadcast were dropped) and a lost alert cadence is repaired.
- `onDestroy` re-arms the watchdog, so an unexpected death self-heals; an
  explicit Stop cancels it. A refused foreground promotion stops the service
  cleanly (Android enforces a promotion timeout regardless), and the
  alarm-exempt watchdog revives it within one tick — the user's monitoring
  intent is preserved, so the notification always comes back.
- The charging pause and unknown-level states keep a slow self-armed re-check
  running, so resuming never depends solely on plug/unplug broadcasts.

The Start flow also keeps requesting the battery-optimization exemption, and
the main screen shows a warning while the app is still battery-restricted.
Android force-stop is special: once force-stopped, Android will not run the
boot receiver or any alarm until the app is manually opened — no app can
survive that, by OS design.

## Battery usage

IDLE mode has no polling loop. The app listens for battery events and only
starts the active cycle below the highest threshold. While ACTIVE, the normal
cadence runs on the main-looper handler; as a doze backstop, one inexact
`setAndAllowWhileIdle` alarm is armed per pending cycle so the CPU sleeping
cannot silently freeze the alerts — Android throttles that alarm in deep doze,
which is the intended battery floor. The liveness watchdog adds one exact
wake-up every ~15 minutes (a few milliseconds of work; notification re-post +
boolean checks) and only while monitoring is on. There are no network, GPS, or
wake-lock operations.

## About, licenses, and privacy

The main screen's **About** button opens an in-app legal screen showing the app
version, a privacy statement, the MIT license, the third-party notices for the
bundled libraries, and the full Apache-2.0 license text. The texts are
generated at build time from `LICENSE`, `NOTICE`, and
`LICENSES/Apache-2.0.txt`, so each is maintained in exactly one place.

The app collects, transmits, and shares no data. It declares no network
permission: battery level is read only on the device, and settings stay in
local storage.