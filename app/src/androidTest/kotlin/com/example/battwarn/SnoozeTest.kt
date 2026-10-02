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
 * Verifies the per-threshold snooze: a tier with [AlertThreshold.allowSnooze]
 * offers a "Snooze" button on the active alert's notification; tapping it
 * pauses that tier's banners/sound (the notification flips to "(snoozed)" and
 * the button becomes "Show alerts"), a tap on "Show alerts" resumes them, and
 * crossing into a different tier releases the snooze automatically. Tiers
 * without the flag never show the button, and the charging pause hides it.
 */
@RunWith(AndroidJUnit4::class)
class SnoozeTest {

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
    fun snoozeButton_followsAllowSnoozeFlag() {
        AppSettings.saveThresholds(
            context,
            listOf(tier(90, "Silent tier").copy(allowSnooze = false))
        )
        dischargeTo(50)
        startService()

        assertEquals(
            "tier without the flag must not offer a button",
            emptyList<String>(),
            actionTitles()
        )

        AppSettings.saveThresholds(
            context,
            listOf(tier(90, "Snoozable tier").copy(allowSnooze = true))
        )
        // Restart so the running service rebuilds the notification.
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        assertTrue(
            "service did not stop",
            await(10_000) { !BatteryMonitorService.isRunning }
        )
        ContextCompat.startForegroundService(
            context,
            Intent(context, BatteryMonitorService::class.java)
        )
        assertTrue("service did not restart", await(10_000) { BatteryMonitorService.isRunning })
        assertEquals(listOf(context.getString(R.string.notif_action_snooze)), actionTitles())
    }

    @Test
    fun snooze_freezesBanners_untilShowAlerts() {
        AppSettings.saveThresholds(context, listOf(tier(90, "Low").copy(allowSnooze = true)))
        dischargeTo(50)
        startService()

        val shown = BatteryMonitorService.bannerShows
        assertTrue("banner must alert before snoozing", await(10_000) { BatteryMonitorService.bannerShows > shown })
        assertTrue("snooze button missing", tapAction(context.getString(R.string.notif_action_snooze)))

        val snoozedTitle = context.getString(R.string.notif_title_snoozed, "Low")
        assertTrue(
            "notification must show the snoozed tier",
            await(10_000) { notificationTitle() == snoozedTitle }
        )
        assertEquals(
            "snoozed notification must offer re-enabling",
            listOf(context.getString(R.string.notif_action_unsnooze)),
            actionTitles()
        )

        // Frozen: two full intervals must not produce a new banner.
        val frozen = BatteryMonitorService.bannerShows
        Thread.sleep(12_000)
        assertEquals("snoozed tier must not alert", frozen, BatteryMonitorService.bannerShows)

        assertTrue(
            "show-alerts button missing",
            tapAction(context.getString(R.string.notif_action_unsnooze))
        )
        assertTrue(
            "re-enabling must restore the plain tier title",
            await(10_000) { notificationTitle() == "Low" }
        )
        assertTrue(
            "alerts must resume after re-enabling",
            await(10_000) { BatteryMonitorService.bannerShows > frozen }
        )
    }

    @Test
    fun crossingIntoNextTier_releasesSnooze() {
        AppSettings.saveThresholds(
            context,
            listOf(
                tier(60, "Sixty").copy(allowSnooze = true),
                tier(30, "Thirty").copy(allowSnooze = true),
            )
        )
        dischargeTo(40)
        startService()

        val shown = BatteryMonitorService.bannerShows
        assertTrue("banner must alert", await(10_000) { BatteryMonitorService.bannerShows > shown })
        assertTrue("snooze button missing", tapAction(context.getString(R.string.notif_action_snooze)))
        assertTrue(
            "notification must show the snoozed tier",
            await(10_000) { notificationTitle() == context.getString(R.string.notif_title_snoozed, "Sixty") }
        )

        val frozen = BatteryMonitorService.bannerShows
        Thread.sleep(8_000)
        assertEquals("snoozed tier must stay quiet", frozen, BatteryMonitorService.bannerShows)

        // Drop below the next threshold: the new tier alerts fresh and is
        // independently snoozable.
        shell("dumpsys battery set level 20")
        assertTrue(
            "crossing into the next tier must alert",
            await(10_000) {
                val title = notificationTitle()
                title == "Thirty" && BatteryMonitorService.bannerShows > frozen
            }
        )
        assertEquals(
            "the new tier gets its own snooze button",
            listOf(context.getString(R.string.notif_action_snooze)),
            actionTitles()
        )
    }

    @Test
    fun chargingSuppression_hidesSnoozeButton() {
        AppSettings.saveThresholds(context, listOf(tier(90, "Low").copy(allowSnooze = true)))
        shell("dumpsys battery set ac 1")
        shell("dumpsys battery set status 2")
        shell("dumpsys battery set level 50")
        startService()

        assertTrue(
            "notification must stay at monitoring while charging",
            await(10_000) {
                notificationTitle() == context.getString(R.string.notif_title_monitoring)
            }
        )
        assertEquals(
            "the charging pause must not offer a snooze button",
            emptyList<String>(),
            actionTitles()
        )
    }

    @Test
    fun allowSnooze_roundTripsThroughSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0).findViewById<MaterialCheckBox>(R.id.allow_snooze_check)
                assertFalse("snoozing must default unchecked", check.isChecked)
                check.performClick()
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertTrue(
            "saving must persist the checked snooze flag",
            AppSettings.thresholds(context).first().allowSnooze
        )
    }

    @Test
    fun allowSnooze_loadsSavedValue() {
        AppSettings.saveThresholds(
            context,
            listOf(AppSettings.DEFAULT_NEW_THRESHOLD.copy(allowSnooze = true))
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0).findViewById<MaterialCheckBox>(R.id.allow_snooze_check)
                assertTrue("saved (on) value must be reflected on load", check.isChecked)
            }
        }
    }

    /** Discharging below a tier with the simulator quirks handled (explicit status). */
    private fun dischargeTo(level: Int) {
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set status 3")
        shell("dumpsys battery set level $level")
    }

    private fun tier(level: Int, name: String): AlertThreshold =
        AppSettings.DEFAULT_NEW_THRESHOLD.copy(
            level = level,
            name = name,
            intervalSec = 5,
            durationSec = 1,
            lockScreen = false
        )

    /** Fires the notification action with [title]; false when it is absent. */
    private fun tapAction(title: String): Boolean {
        val action = notification()?.actions?.firstOrNull { it.title.toString() == title }
            ?: return false
        action.actionIntent.send()
        return true
    }

    private fun notification(): Notification? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.notification

    private fun notificationTitle(): String? =
        notification()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun actionTitles(): List<String> =
        notification()?.actions?.map { it.title.toString() } ?: emptyList()

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
