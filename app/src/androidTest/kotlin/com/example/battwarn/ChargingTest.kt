package com.example.battwarn

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
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
 * Verifies the "Warn only on battery" setting: with it on, charging (AC/
 * USB/wireless) pauses all warnings; unplugging resumes them below the
 * threshold; with it off, warnings continue while charging.
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
            "banner must not show while charging (setting on)",
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
            "banner must show while charging (setting off)",
            await(10_000) { BatteryMonitorService.bannerShows > before }
        )
    }

    private fun setPrefs(warnOnlyOnBattery: Boolean) {
        AppSettings.prefs(context).edit()
            .putBoolean(AppSettings.KEY_WARN_ONLY_ON_BATTERY, warnOnlyOnBattery)
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, 90)
            .putInt(AppSettings.KEY_POLL_INTERVAL_SEC, 5)
            .putInt(AppSettings.KEY_TOAST_DURATION_SEC, 1)
            .commit()
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