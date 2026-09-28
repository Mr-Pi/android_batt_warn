// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for the threshold model: the active-tier selection,
 * the JSON codec, color parsing, and clamping. These run on the JVM without
 * an emulator.
 */
class AlertThresholdTest {

    private val low = AlertThreshold(
        level = 20, name = "Battery low", intervalSec = 25, durationSec = 5,
        soundUri = "", lockScreen = true, overrideSilentDnd = false,
        bgColor = 0xFFD32F2F.toInt(), fgColor = 0xFFFFFFFF.toInt()
    )
    private val critical = low.copy(
        level = 9, name = "Battery critical", intervalSec = 15, durationSec = 5
    )

    @Test
    fun activeThreshold_picksSmallestApplicable() {
        val list = listOf(low, critical)
        assertNull("at or above every threshold must be IDLE", activeThreshold(20, list))
        assertNull("exactly at the highest threshold is not below it", activeThreshold(20, list))
        assertEquals("20 applies between 9 and 20", low.level, activeThreshold(15, list)!!.level)
        assertEquals("9 applies below 9", critical.level, activeThreshold(8, list)!!.level)
        assertEquals("9 is not below 9, so 20 applies", low.level, activeThreshold(9, list)!!.level)
    }

    @Test
    fun highestThreshold_returnsLargestLevel() {
        assertEquals(20, highestThreshold(listOf(low, critical))!!.level)
        assertEquals(low.level, highestThreshold(listOf(low))!!.level)
    }

    @Test
    fun activeThreshold_returnsNullForEmptyList() {
        assertNull(activeThreshold(5, emptyList()))
    }

    @Test
    fun encodeDecode_roundTripsAndSortsDescending() {
        val json = encodeThresholds(listOf(critical, low))
        val decoded = decodeThresholds(json)
        assertEquals(listOf(low, critical), decoded)
        assertEquals(listOf(low, critical), decodeThresholds(encodeThresholds(decoded)))
    }

    @Test
    fun decode_handlesBlankAndGarbage() {
        assertTrue(decodeThresholds(null).isEmpty())
        assertTrue(decodeThresholds("").isEmpty())
        assertTrue(decodeThresholds("not json").isEmpty())
    }

    @Test
    fun decode_clampsLevelsAndIntervals() {
        val json = encodeThresholds(
            listOf(
                low.copy(level = 150, intervalSec = 0, durationSec = -3),
                critical.copy(level = -5)
            )
        )
        val decoded = decodeThresholds(json)
        assertEquals(100, decoded.first { it.level == 100 }.level)
        assertEquals(1, decoded.first { it.level == 100 }.intervalSec)
        assertEquals(1, decoded.first { it.level == 100 }.durationSec)
        assertEquals(0, decoded.first { it.level == 0 }.level)
    }

    @Test
    fun colorToHex_andParse_roundTrip() {
        val color = 0xFF1976D2.toInt()
        assertEquals("#1976D2", colorToHex(color))
        assertEquals(color, parseHexColor(colorToHex(color), 0))
        assertEquals(0xFF1976D2.toInt(), parseHexColor("#1976D2", 0))
        assertEquals(0xFFFF0000.toInt(), parseHexColor("#F00", 0))
        assertEquals(0xFFABCDEF.toInt(), parseHexColor("#FFABCDEF", 0))
        assertEquals(0, parseHexColor("garbage", 0))
        assertEquals(0, parseHexColor("", 0))
    }
}