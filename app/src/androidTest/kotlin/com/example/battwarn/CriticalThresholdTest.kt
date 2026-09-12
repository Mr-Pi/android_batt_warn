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
 * Verifies the two-tier alert: between the critical and alert thresholds the
 * notification shows the warning title; below the critical threshold it flips
 * to the critical title. [AppSettings.criticalThreshold] must also stay at or
 * below the alert threshold.
 */
@RunWith(AndroidJUnit4::class)
class CriticalThresholdTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun levelBetweenThresholds_showsWarningTitle() {
        setTiers(alert = 90, critical = 50)

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            await(10_000) { BatteryMonitorService.isRunning }

            // Between critical (50) and alert (90): warning tier.
            shell("dumpsys battery set level 60")

            val warningTitle = context.getString(R.string.notif_title_warning)
            assertTrue("notification did not show the warning title", await(10_000) {
                notificationTitle() == warningTitle
            })
        }
    }

    @Test
    fun levelBelowCritical_showsCriticalTitle() {
        setTiers(alert = 90, critical = 50)

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            await(10_000) { BatteryMonitorService.isRunning }

            // Below the critical threshold: critical tier.
            shell("dumpsys battery set level 40")

            val criticalTitle = context.getString(R.string.notif_title_critical)
            assertTrue("notification did not show the critical title", await(10_000) {
                notificationTitle() == criticalTitle
            })
        }
    }

    @Test
    fun criticalThreshold_clampsToAlertThreshold() {
        AppSettings.prefs(context).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, 20)
            .putInt(AppSettings.KEY_CRITICAL_THRESHOLD, 80)
            .commit()
        assertEquals(20, AppSettings.criticalThreshold(context))
    }

    private fun setTiers(alert: Int, critical: Int) {
        AppSettings.prefs(context).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, alert)
            .putInt(AppSettings.KEY_CRITICAL_THRESHOLD, critical)
            .commit()
    }

    private fun notificationTitle(): String? =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.packageName == context.packageName }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

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