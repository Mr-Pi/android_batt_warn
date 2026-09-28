// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the per-threshold "Warn only on battery" flag: the active tier
 * decides. With the flag on, charging (AC/USB/wireless) pauses all warnings;
 * unplugging resumes them below the threshold; with it off, warnings continue
 * while charging. A higher tier with the flag off still warns while charging
 * above a lower tier that has it on.
 */
@RunWith(AndroidJUnit4::class)
class ChargingTest {

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
    fun chargingWithSettingOn_showsNoBanner() {
        setPrefs(warnOnlyOnBattery = true)
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set level 50")

        val before = BatteryMonitorService.bannerShows
        startService()

        // Cycles would repeat every ~5s without suppression; give it enough
        // time to prove the banner never appears while charging.
        Thread.sleep(10_000)
        assertTrue(
            "banner must not show while charging (flag on)",
            BatteryMonitorService.bannerShows == before
        )
        assertEquals(
            "notification must stay at monitoring while charging",
            context.getString(R.string.notif_title_monitoring),
            notificationTitle()
        )
    }

    @Test
    fun unplug_resumesWarnings() {
        setPrefs(warnOnlyOnBattery = true)
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set level 50")

        val before = BatteryMonitorService.bannerShows
        startService()
        Thread.sleep(4000)
        assertTrue(
            "precondition: no banner while charging",
            BatteryMonitorService.bannerShows == before
        )

        shell("dumpsys battery set ac 0")
        assertTrue(
            "banner must resume after unplugging",
            await(10_000) { BatteryMonitorService.bannerShows > before }
        )
    }

    @Test
    fun chargingWithSettingOff_stillWarns() {
        setPrefs(warnOnlyOnBattery = false)
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set level 50")

        val before = BatteryMonitorService.bannerShows
        startService()
        assertTrue(
            "banner must show while charging (flag off)",
            await(10_000) { BatteryMonitorService.bannerShows > before }
        )
    }

    @Test
    fun chargingSuppression_followsActiveTier() {
        // 60% tier warns while charging; the 50% tier (below 50%) does not.
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                    level = 60, name = "Sixty", intervalSec = 5, durationSec = 1,
                    lockScreen = false, warnOnlyOnBattery = false
                ),
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                    level = 50, name = "Fifty", intervalSec = 5, durationSec = 1,
                    lockScreen = false, warnOnlyOnBattery = true
                ),
            )
        )
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set level 55")

        val before = BatteryMonitorService.bannerShows
        startService()
        assertTrue(
            "warns while charging when the active tier allows it",
            await(10_000) { BatteryMonitorService.bannerShows > before }
        )

        // Below 50% the 50% tier (flag on) becomes active: suppress.
        val mid = BatteryMonitorService.bannerShows
        shell("dumpsys battery set level 45")
        Thread.sleep(3_000)
        assertEquals(
            "charging + active tier warn-only must suppress",
            mid,
            BatteryMonitorService.bannerShows
        )

        // Back above 50% the 60% tier (flag off) resumes.
        shell("dumpsys battery set level 55")
        assertTrue(
            "warns again once the allowed tier is active",
            await(10_000) { BatteryMonitorService.bannerShows > mid }
        )
    }

    @Test
    fun warnOnlyOnBattery_roundTripsThroughSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0)
                    .findViewById<MaterialCheckBox>(R.id.warn_on_battery_check)
                assertTrue("must start checked with the flag on by default", check.isChecked)

                check.performClick()
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertFalse(
            "saving must persist the unchecked warn-only flag",
            AppSettings.thresholds(context).first().warnOnlyOnBattery
        )
    }

    @Test
    fun warnOnlyOnBattery_loadsSavedValue() {
        AppSettings.saveThresholds(
            context,
            listOf(AppSettings.DEFAULT_NEW_THRESHOLD.copy(warnOnlyOnBattery = false))
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0)
                    .findViewById<MaterialCheckBox>(R.id.warn_on_battery_check)
                assertFalse("saved (off) value must be reflected on load", check.isChecked)
            }
        }
    }

    private fun setPrefs(warnOnlyOnBattery: Boolean) {
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                    level = 90,
                    name = "Battery low",
                    intervalSec = 5,
                    durationSec = 1,
                    lockScreen = false,
                    warnOnlyOnBattery = warnOnlyOnBattery
                )
            )
        )
    }

    private fun rowAt(activity: MainActivity, index: Int): View {
        val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
        return container.getChildAt(index)
    }

    private fun startService() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.let { ui ->
            try {
                ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                // Already granted.
            }
        }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
        }
    }

    private fun notificationTitle(): String? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** Runs a shell command and waits for it to finish (drains the output). */
    private fun shell(cmd: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val buffer = ByteArray(4096)
            while (input.read(buffer) != -1) {
                // Drain until the command completes.
            }
        }
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