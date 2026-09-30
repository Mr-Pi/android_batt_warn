// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies multi-threshold selection: with two thresholds, the level between
 * them shows the higher tier's name, below the lower threshold it flips to the
 * lower tier's name, and the pure selection helper matches the service.
 */
@RunWith(AndroidJUnit4::class)
class ThresholdSelectionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        // Ensure startForeground already ran (isRunning flips after it) to
        // avoid the system's "did not call startForeground in time" crash,
        // then wait for the service to be really gone so the next test does not
        // race a still-live instance publishing a stale notification title.
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            await(8_000L) { !BatteryMonitorService.isRunning }
        }
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun levelBetweenThresholds_showsHigherTierName() {
        setThresholds()
        startService()

        // Between 50 and 90: the 90 tier ("Battery low") applies.
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set level 60")

        assertTrue("notification did not show the higher tier name", await(10_000) {
            notificationTitle() == "Battery low"
        })
    }

    @Test
    fun levelBelowLowerThreshold_showsLowerTierName() {
        setThresholds()
        startService()

        // Below 50: the 50 tier ("Battery critical") applies.
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set level 40")

        assertTrue("notification did not show the lower tier name", await(10_000) {
            notificationTitle() == "Battery critical"
        })
    }

    @Test
    fun levelExactlyAtLowerThreshold_usesHigherTier() {
        setThresholds()
        startService()

        // At exactly 50 it is not below 50 yet, so the 90 tier still applies.
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set level 50")

        assertTrue("notification must stay on the higher tier at the boundary", await(10_000) {
            notificationTitle() == "Battery low"
        })
    }

    @Test
    fun activeThreshold_selectsMostSevereApplicable() {
        val list = listOf(threshold(90, "Battery low"), threshold(50, "Battery critical"))
        assertEquals("90 applies between the thresholds", 90, activeThreshold(60, list)!!.level)
        assertEquals("50 applies below 50", 50, activeThreshold(49, list)!!.level)
        assertEquals("50 applies at the lower boundary when strictly below", 50, activeThreshold(40, list)!!.level)
        assertNull("no threshold below at/above the highest", activeThreshold(90, list))
        assertNull("no threshold at full battery", activeThreshold(100, list))
    }

    private fun setThresholds() {
        AppSettings.saveThresholds(
            context,
            listOf(threshold(90, "Battery low"), threshold(50, "Battery critical"))
        )
    }

    private fun startService() {
        grantNotificationPermission()
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
        }
    }

    private fun threshold(level: Int, name: String): AlertThreshold =
        AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = level, name = name)

    private fun notificationTitle(): String? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun grantNotificationPermission() {
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            // Already granted.
        }
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}