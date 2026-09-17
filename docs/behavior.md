# Behavior and settings

## Warning and critical tiers

The warning threshold starts the monitor's active behavior. The lower critical
threshold changes the timing, notification title, and sound behavior:

- Warning: `critical_threshold <= battery < alert_threshold`; no sound.
- Critical: `battery < critical_threshold`; selected sound plays once per
  critical alert cycle, unless the device ringer is silent or vibrate.

Both tiers use the same scheduling rule: the configured interval is a quiet
pause **after** the banner closes. Tapping the banner closes it early and starts
that pause immediately.

## Charging

**Warn only on battery** is enabled by default. When the device is plugged into
AC, USB, or wireless power, warning and critical banners/sounds stop. The
notification still shows the live battery level and uses the monitoring title.
Unplugging while still below the threshold resumes the first alert immediately.

Turning the setting off allows warnings while charging.

## Alert sound

Use **Pick sound** to open Android's notification ringtone picker. **Remove
sound** or choosing **Silent** stores an empty sound URI and disables playback.
The service also respects Android's silent/vibrate ringer state.

**Play critical sound in silent / Do Not Disturb** (off by default) makes the
critical alert sound play even when the ringer is silent, on vibrate, or the
device is in Do Not Disturb. It routes the tone to the alarm stream, which
Android does not mute with a silent ringer and exempts from typical DND. The
one case it cannot pierce is a DND rule configured to silence alarms as well.

## Overlay and notification fallback

With the “Display over other apps” permission, the service displays a red bottom
banner above other apps. It is non-focusable, auto-dismisses, and can be tapped
to close. Without that special permission, the foreground notification remains
the reliable alert surface.

Some apps using `FLAG_SECURE` may block overlays; the notification remains
available in that case.

## Autostart and reliability

The boot receiver handles device boot and package replacement. The service uses
`START_STICKY` and requests a battery-optimization exemption from the Start
flow. Android force-stop is special: once force-stopped, Android will not run
the boot receiver again until the app is manually opened.

## Battery usage

IDLE mode has no polling loop or scheduled alarm. The app listens for battery
events and only starts the active cycle below the alert threshold. There are no
network, GPS, or wake-lock operations.
