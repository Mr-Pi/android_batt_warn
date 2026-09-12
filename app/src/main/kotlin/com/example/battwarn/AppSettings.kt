package com.example.battwarn

import android.content.Context

/** Central place for the app's configurable settings (SharedPreferences). */
object AppSettings {
    const val PREFS_NAME = "battwarn_settings"

    const val KEY_ALERT_THRESHOLD = "alert_threshold"
    const val KEY_POLL_INTERVAL_SEC = "poll_interval_sec"
    const val KEY_TOAST_DURATION_SEC = "toast_duration_sec"

    const val DEFAULT_ALERT_THRESHOLD = 10
    const val DEFAULT_POLL_INTERVAL_SEC = 25
    const val DEFAULT_TOAST_DURATION_SEC = 5

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Alert level in percent (0..100). */
    fun alertThreshold(context: Context): Int =
        prefs(context).getInt(KEY_ALERT_THRESHOLD, DEFAULT_ALERT_THRESHOLD).coerceIn(0, 100)

    /** Interval in milliseconds between toasts while below the alert level. */
    fun pollIntervalMillis(context: Context): Long =
        prefs(context).getInt(KEY_POLL_INTERVAL_SEC, DEFAULT_POLL_INTERVAL_SEC)
            .coerceIn(5, 3600) * 1000L

    /** How long each alert toast stays on screen, in milliseconds. */
    fun toastDurationMillis(context: Context): Long =
        prefs(context).getInt(KEY_TOAST_DURATION_SEC, DEFAULT_TOAST_DURATION_SEC)
            .coerceIn(1, 30) * 1000L
}