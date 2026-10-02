// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the threshold-list settings: the fresh-install baseline, the JSON
 * persistence round-trip (including colors and clamping), and selection from
 * the stored list.
 */
@RunWith(AndroidJUnit4::class)
class ThresholdConfigTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun defaults_areTheFreshBaseline() {
        val defaults = AppSettings.thresholds(context)
        assertEquals(2, defaults.size)
        assertEquals(20, defaults[0].level)
        assertEquals("Battery low", defaults[0].name)
        assertEquals(9, defaults[1].level)
        assertEquals("Battery critical", defaults[1].name)
        assertTrue("both defaults show on the lock screen", defaults.all { it.lockScreen })
        assertTrue("both defaults honor silent/DND", defaults.all { !it.overrideSilentDnd })
        assertTrue("both defaults warn only on battery", defaults.all { it.warnOnlyOnBattery })
        assertTrue("both defaults disallow snoozing", defaults.all { !it.allowSnooze })
        assertEquals(DEFAULT_BG_COLOR, defaults[0].bgColor)
        assertEquals(DEFAULT_FG_COLOR, defaults[0].fgColor)
    }

    @Test
    fun saveLoad_roundTripsCustomList() {
        val list = listOf(
            AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                level = 30,
                name = "Custom",
                bgColor = 0xFF0000FF.toInt(),
                fgColor = 0xFFFFFF00.toInt()
            ),
            AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                level = 5,
                name = "",
                soundUri = "content://settings/system/notification_sound",
                lockScreen = false,
                overrideSilentDnd = true,
                warnOnlyOnBattery = false
            ),
        )
        AppSettings.saveThresholds(context, list)
        assertEquals(list, AppSettings.thresholds(context))
    }

    @Test
    fun save_sortsDescendingOnReload() {
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 5),
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 30),
            )
        )
        assertEquals(
            listOf(30, 5),
            AppSettings.thresholds(context).map { it.level }
        )
    }

    @Test
    fun selection_usesStoredList() {
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 90, name = "A"),
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 50, name = "B"),
            )
        )
        val stored = AppSettings.thresholds(context)
        assertEquals("A", activeThreshold(60, stored)!!.name)
        assertEquals("B", activeThreshold(40, stored)!!.name)
        assertNull(activeThreshold(100, stored))
        assertEquals(90, highestThreshold(stored)!!.level)
    }
}