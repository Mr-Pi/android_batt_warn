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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end regression test for threshold detection: when the battery drops
 * below the highest configured level, the service must enter ACTIVE mode and
 * the notification must switch to the active threshold's name showing the
 * current level — still above any lower threshold, so no sound.
 */
@RunWith(AndroidJUnit4::class)
class BatteryDropTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        // Ensure startForeground already ran (isRunning flips after it) to
        // avoid the system's "did not call startForeground in time" crash.
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun batteryDropBelowAlertThreshold_showsThresholdName() {
        grantNotificationPermission()

        // Single high threshold so the emulator's default 100% starts in IDLE.
        AppSettings.saveThresholds(context, listOf(threshold(90, "Battery low")))

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })

            // Drop the battery below the threshold.
            shell("dumpsys battery set ac 0")
            shell("dumpsys battery set level 50")

            val nm = context.getSystemService(NotificationManager::class.java)

            assertTrue(
                "notification did not show the threshold name",
                await(10_000) {
                    nm.activeNotifications.any {
                        it.notification.extras
                            .getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Battery low"
                    }
                }
            )

            val expectedText = context.getString(R.string.notif_text_level, 50)
            val active = nm.activeNotifications.first { it.packageName == context.packageName }
            val text = active.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("notification must show the current battery level", expectedText, text)
        }
    }

    private fun threshold(level: Int, name: String): AlertThreshold =
        AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = level, name = name)

    private fun grantNotificationPermission() {
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            // Already granted or denied by policy; assertions below will catch problems.
        }
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd).close()
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