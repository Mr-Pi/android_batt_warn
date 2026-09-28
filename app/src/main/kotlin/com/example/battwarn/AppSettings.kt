// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context

/** Central place for the app's configurable settings (SharedPreferences). */
object AppSettings {
    const val PREFS_NAME = "battwarn_settings"

    const val KEY_THRESHOLDS = "thresholds"
    const val KEY_WARN_ONLY_ON_BATTERY = "warn_only_on_battery"

    const val DEFAULT_WARN_ONLY_ON_BATTERY = true

    /**
     * A fresh-install baseline: a warning tier at 20% and a critical tier at
     * 9%, matching the app's original two-tier spirit. Per the project's
     * decision, legacy single keys are ignored (no migration); when no
     * threshold list is stored, this list is returned.
     */
    val DEFAULT_THRESHOLDS: List<AlertThreshold> = listOf(
        AlertThreshold(
            level = 20,
            name = "Battery low",
            intervalSec = DEFAULT_INTERVAL_SEC,
            durationSec = DEFAULT_DURATION_SEC,
            soundUri = "",
            lockScreen = true,
            overrideSilentDnd = false,
            bgColor = DEFAULT_BG_COLOR,
            fgColor = DEFAULT_FG_COLOR,
        ),
        AlertThreshold(
            level = 9,
            name = "Battery critical",
            intervalSec = 15,
            durationSec = DEFAULT_DURATION_SEC,
            soundUri = "",
            lockScreen = true,
            overrideSilentDnd = false,
            bgColor = DEFAULT_BG_COLOR,
            fgColor = DEFAULT_FG_COLOR,
        ),
    )

    /** Settings for a threshold the user is about to add. */
    val DEFAULT_NEW_THRESHOLD: AlertThreshold = AlertThreshold(
        level = 15,
        name = "",
        intervalSec = DEFAULT_INTERVAL_SEC,
        durationSec = DEFAULT_DURATION_SEC,
        soundUri = "",
        lockScreen = true,
        overrideSilentDnd = false,
        bgColor = DEFAULT_BG_COLOR,
        fgColor = DEFAULT_FG_COLOR,
    )

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The configured threshold list, falling back to [DEFAULT_THRESHOLDS]. */
    fun thresholds(context: Context): List<AlertThreshold> =
        decodeThresholds(prefs(context).getString(KEY_THRESHOLDS, null)).ifEmpty {
            DEFAULT_THRESHOLDS
        }

    /** Persists the threshold list (already validated by the caller). */
    fun saveThresholds(context: Context, thresholds: List<AlertThreshold>) {
        prefs(context).edit()
            .putString(KEY_THRESHOLDS, encodeThresholds(thresholds))
            .apply()
    }

    /**
     * True (default) pauses all warnings while the device is charging;
     * false keeps warning on AC/USB/wireless too.
     */
    fun warnOnlyOnBattery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WARN_ONLY_ON_BATTERY, DEFAULT_WARN_ONLY_ON_BATTERY)

    /** Persists the "Warn only on battery" checkbox. */
    fun saveWarnOnlyOnBattery(context: Context, warnOnlyOnBattery: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_WARN_ONLY_ON_BATTERY, warnOnlyOnBattery)
            .apply()
    }
}

/** The threshold's name, falling back to a generic label when blank. */
fun AlertThreshold.displayName(context: Context): String =
    name.ifBlank { context.getString(R.string.alert_name_default) }

/** Alert message prefix for a banner: "<name>: <level>%". */
fun alertMessage(context: Context, threshold: AlertThreshold, level: Int): String =
    context.getString(R.string.alert_message_format, threshold.displayName(context), level)