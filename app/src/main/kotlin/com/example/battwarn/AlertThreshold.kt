// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import org.json.JSONArray
import org.json.JSONObject

/**
 * One user-configured alert tier. The service alerts while the battery level
 * is below [level]; among all thresholds the level is below, the one with the
 * smallest [level] (the most severe applicable tier) is the active threshold
 * and its settings drive the alert.
 *
 * Colors are opaque ARGB ints (e.g. [DEFAULT_BG_COLOR]); they are persisted
 * as `#RRGGBB` strings so the stored settings stay readable.
 */
data class AlertThreshold(
    val level: Int,
    val name: String,
    val intervalSec: Int,
    val durationSec: Int,
    val soundUri: String,
    val lockScreen: Boolean,
    val overrideSilentDnd: Boolean,
    val bgColor: Int,
    val fgColor: Int,
    val warnOnlyOnBattery: Boolean = true,
)

const val DEFAULT_INTERVAL_SEC = 25
const val DEFAULT_DURATION_SEC = 5

const val DEFAULT_BG_COLOR = 0xFFD32F2F.toInt() // material red 700
const val DEFAULT_FG_COLOR = 0xFFFFFFFF.toInt() // white

/** Built-in swatches for the per-threshold background/text pickers. */
val ALERT_PALETTE: List<Int> = listOf(
    0xFFD32F2F.toInt(), // red
    0xFFF57C00.toInt(), // orange
    0xFFFFB300.toInt(), // amber
    0xFF388E3C.toInt(), // green
    0xFF00796B.toInt(), // teal
    0xFF1976D2.toInt(), // blue
    0xFF303F9F.toInt(), // indigo
    0xFF7B1FA2.toInt(), // purple
    0xFFC2185B.toInt(), // pink
    0xFF455A64.toInt(), // blue gray
    0xFF000000.toInt(), // black
    0xFFFFFFFF.toInt(), // white
)

/**
 * The active tier at [level]: the threshold with the smallest level that the
 * level is strictly below. Returns null when the level is at or above every
 * threshold (the service stays IDLE).
 */
fun activeThreshold(level: Int, thresholds: List<AlertThreshold>): AlertThreshold? =
    thresholds.filter { level < it.level }.minByOrNull { it.level }

/** The highest (least severe) threshold — the entry point for monitoring. */
fun highestThreshold(thresholds: List<AlertThreshold>): AlertThreshold? =
    thresholds.maxByOrNull { it.level }

/** Serializes the list sorted by level descending (most severe first). */
fun encodeThresholds(thresholds: List<AlertThreshold>): String =
    JSONArray().apply {
        thresholds.sortedByDescending { it.level }.forEach { put(it.toJson()) }
    }.toString()

/** Parses a serialized list; returns an empty list for blank/garbage input. */
fun decodeThresholds(json: String?): List<AlertThreshold> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { index -> fromJson(array.getJSONObject(index)) }
            .sortedByDescending { it.level }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun AlertThreshold.toJson(): JSONObject = JSONObject().apply {
    put("level", level)
    put("name", name)
    put("intervalSec", intervalSec)
    put("durationSec", durationSec)
    put("soundUri", soundUri)
    put("lockScreen", lockScreen)
    put("overrideSilentDnd", overrideSilentDnd)
    put("bgColor", colorToHex(bgColor))
    put("fgColor", colorToHex(fgColor))
    put("warnOnlyOnBattery", warnOnlyOnBattery)
}

private fun fromJson(json: JSONObject): AlertThreshold = AlertThreshold(
    level = json.optInt("level", 0).coerceIn(0, 100),
    name = json.optString("name", ""),
    intervalSec = json.optInt("intervalSec", DEFAULT_INTERVAL_SEC).coerceAtLeast(1),
    durationSec = json.optInt("durationSec", DEFAULT_DURATION_SEC).coerceAtLeast(1),
    soundUri = json.optString("soundUri", ""),
    lockScreen = json.optBoolean("lockScreen", true),
    overrideSilentDnd = json.optBoolean("overrideSilentDnd", false),
    bgColor = parseHexColor(json.optString("bgColor", ""), DEFAULT_BG_COLOR),
    fgColor = parseHexColor(json.optString("fgColor", ""), DEFAULT_FG_COLOR),
    warnOnlyOnBattery = json.optBoolean("warnOnlyOnBattery", true),
)

/** Renders an opaque ARGB int as `#RRGGBB`. */
fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

/**
 * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` into an ARGB int. Pure Kotlin so the
 * codec stays JVM-testable; [fallback] is returned for anything unparsable.
 */
fun parseHexColor(hex: String, fallback: Int): Int {
    if (hex.isBlank()) return fallback
    return try {
        val h = hex.removePrefix("#")
        when (h.length) {
            3 -> {
                val r = h[0].digitToInt(16) * 0x110000
                val g = h[1].digitToInt(16) * 0x1100
                val b = h[2].digitToInt(16) * 0x11
                0xFF000000.toInt() or r or g or b
            }
            6 -> 0xFF000000.toInt() or h.toLong(16).toInt()
            8 -> h.toLong(16).toInt()
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}