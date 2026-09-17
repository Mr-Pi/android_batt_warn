// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context

/** Central place for the app's configurable settings (SharedPreferences). */
object AppSettings {
    const val PREFS_NAME = "battwarn_settings"

    const val KEY_ALERT_THRESHOLD = "alert_threshold"
    const val KEY_POLL_INTERVAL_SEC = "poll_interval_sec"
    const val KEY_TOAST_DURATION_SEC = "toast_duration_sec"
    const val KEY_CRITICAL_THRESHOLD = "critical_threshold"
    const val KEY_CRITICAL_INTERVAL_SEC = "critical_interval_sec"
    const val KEY_CRITICAL_DURATION_SEC = "critical_duration_sec"
    const val KEY_CRITICAL_SOUND_URI = "critical_sound_uri"
    const val KEY_WARN_ONLY_ON_BATTERY = "warn_only_on_battery"
    const val KEY_OVERRIDE_SILENT_DND = "override_silent_dnd"

    const val DEFAULT_ALERT_THRESHOLD = 10
    const val DEFAULT_POLL_INTERVAL_SEC = 25
    const val DEFAULT_TOAST_DURATION_SEC = 5
    const val DEFAULT_CRITICAL_THRESHOLD = 5
    const val DEFAULT_CRITICAL_INTERVAL_SEC = 15
    const val DEFAULT_CRITICAL_DURATION_SEC = 5
    const val DEFAULT_CRITICAL_SOUND_URI = ""
    const val DEFAULT_WARN_ONLY_ON_BATTERY = true
    const val DEFAULT_OVERRIDE_SILENT_DND = false

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Alert level in percent (0..100). */
    fun alertThreshold(context: Context): Int =
        prefs(context).getInt(KEY_ALERT_THRESHOLD, DEFAULT_ALERT_THRESHOLD).coerceIn(0, 100)

    /** Quiet pause, in milliseconds, after an alert banner closes before the next one shows. */
    fun pollIntervalMillis(context: Context): Long =
        prefs(context).getInt(KEY_POLL_INTERVAL_SEC, DEFAULT_POLL_INTERVAL_SEC)
            .coerceAtLeast(1) * 1000L

    /** How long each alert toast stays on screen, in milliseconds. */
    fun toastDurationMillis(context: Context): Long =
        prefs(context).getInt(KEY_TOAST_DURATION_SEC, DEFAULT_TOAST_DURATION_SEC)
            .coerceAtLeast(1) * 1000L

    /** Critical level in percent — always at or below the alert threshold. */
    fun criticalThreshold(context: Context): Int =
        prefs(context).getInt(KEY_CRITICAL_THRESHOLD, DEFAULT_CRITICAL_THRESHOLD)
            .coerceIn(0, alertThreshold(context))

    /** Quiet pause, in milliseconds, after a critical banner closes before the next one. */
    fun criticalIntervalMillis(context: Context): Long =
        prefs(context).getInt(KEY_CRITICAL_INTERVAL_SEC, DEFAULT_CRITICAL_INTERVAL_SEC)
            .coerceAtLeast(1) * 1000L

    /** How long each critical alert banner stays on screen, in milliseconds. */
    fun criticalDurationMillis(context: Context): Long =
        prefs(context).getInt(KEY_CRITICAL_DURATION_SEC, DEFAULT_CRITICAL_DURATION_SEC)
            .coerceAtLeast(1) * 1000L

    /** Ringtone URI string for the critical alert sound (empty = silent). */
    fun criticalSoundUri(context: Context): String =
        prefs(context).getString(KEY_CRITICAL_SOUND_URI, DEFAULT_CRITICAL_SOUND_URI) ?: ""

    /**
     * True (default) pauses all warnings while the device is charging;
     * false keeps warning on AC/USB/wireless too.
     */
    fun warnOnlyOnBattery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WARN_ONLY_ON_BATTERY, DEFAULT_WARN_ONLY_ON_BATTERY)

    /**
     * True makes the critical alert sound play even when the ringer is silent,
     * on vibrate, or the device is in Do Not Disturb. False (default) honors
     * the ringer state and plays only in RINGER_MODE_NORMAL.
     */
    fun overrideSilentDnd(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERRIDE_SILENT_DND, DEFAULT_OVERRIDE_SILENT_DND)
}