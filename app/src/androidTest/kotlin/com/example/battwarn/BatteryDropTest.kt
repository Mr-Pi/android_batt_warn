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
 * below the configured alert level, the service must enter ACTIVE mode and
 * the notification must switch to the warning title ("Battery low") showing
 * the current level — still above the critical threshold, so no sound.
 */
@RunWith(AndroidJUnit4::class)
class BatteryDropTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun batteryDropBelowAlertThreshold_showsWarningTitle() {
        grantNotificationPermission()

        // High alert threshold so the emulator's default 100% starts in IDLE.
        // Critical stays at its default (5) — this tests the warning tier.
        AppSettings.prefs(context).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, 90)
            .commit()

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })

            // Drop the battery below the alert threshold (but above critical).
            shell("dumpsys battery set level 50")

            val nm = context.getSystemService(NotificationManager::class.java)
            val warningTitle = context.getString(R.string.notif_title_warning)

            assertTrue(
                "notification did not show the warning title",
                await(10_000) {
                    nm.activeNotifications.any {
                        it.notification.extras
                            .getCharSequence(Notification.EXTRA_TITLE)?.toString() == warningTitle
                    }
                }
            )

            val expectedText = context.getString(R.string.notif_text_level, 50)
            val active = nm.activeNotifications.first { it.packageName == context.packageName }
            val text = active.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals("notification must show the current battery level", expectedText, text)
        }
    }

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