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

## Charging

**Warn only on battery** is enabled by default. When the device is plugged into
AC, USB, or wireless power, all banners/sounds stop. The notification still
shows the live battery level and uses the monitoring title. Unplugging while
still below a threshold resumes the first alert immediately.

Turning the setting off allows warnings while charging.

## Alert sound

Use **Pick** on a threshold to open Android's notification ringtone picker.
**Remove** or choosing **Silent** stores an empty sound URI and disables
playback. The service also respects Android's silent/vibrate ringer state.

**Play in silent / DND** (off by default per threshold) makes that tier's alert
sound play even when the ringer is silent, on vibrate, or the device is in Do
Not Disturb. It routes the tone to the alarm stream, which Android does not mute
with a silent ringer and exempts from typical DND. The one case it cannot pierce
is a DND rule configured to silence alarms as well.

## Colors

Each threshold's background and text colors are chosen from a preset palette.
They tint the bottom banner (the rounded background and the message text) and
the full-screen lock-screen alert. The foreground notification itself is not
colored, and the main screen's battery number keeps its green/red rule based on
the highest threshold.

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

The boot receiver handles device boot and package replacement. The service uses
`START_STICKY` and requests a battery-optimization exemption from the Start
flow. Android force-stop is special: once force-stopped, Android will not run
the boot receiver again until the app is manually opened.

## Battery usage

IDLE mode has no polling loop or scheduled alarm. The app listens for battery
events and only starts the active cycle below the highest threshold. There are
no network, GPS, or wake-lock operations.