// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the liveness watchdog: when the service dies while monitoring is on,
 * a watchdog tick restarts it; when the user has stopped monitoring, the
 * watchdog leaves it stopped; while it is alive, a tick re-posts the current
 * notification. Also pins the manifest permissions that make automatic
 * recovery possible without user interaction.
 */
@RunWith(AndroidJUnit4::class)
class MonitorWatchdogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun reset() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        await(8_000L) { !BatteryMonitorService.isRunning }
        MonitorWatchdog.lastArmedAtElapsedMs = -1
        grantNotificationPermission()
    }

    @After
    fun tearDown() {
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            await(8_000L) { !BatteryMonitorService.isRunning }
        }
        shell("dumpsys battery reset")
        context.getSystemService(NotificationManager::class.java)
            .cancel(BatteryMonitorService.NOTIFICATION_ID)
        AppSettings.setMonitoringEnabled(context, false)
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun tick_restartsAServiceThatDiedWhileEnabled() {
        AppSettings.setMonitoringEnabled(context, true)

        // Simulate the service being killed: it is not running, but monitoring
        // is still wanted. A watchdog tick must bring it back.
        assertFalse("precondition: service not running", BatteryMonitorService.isRunning)
        assertTrue("watchdog must attempt a restart", MonitorWatchdog.ensureMonitoring(context))
        assertTrue("service did not come back", await(10_000) { BatteryMonitorService.isRunning })
    }

    @Test
    fun tick_doesNothingWhenTheUserStoppedMonitoring() {
        AppSettings.setMonitoringEnabled(context, false)
        assertFalse("watchdog must not act after a deliberate Stop", MonitorWatchdog.ensureMonitoring(context))
        Thread.sleep(1000)
        assertFalse("service must stay stopped", BatteryMonitorService.isRunning)
    }

    @Test
    fun tick_refreshesInsteadOfRestartingWhileTheServiceIsAlive() {
        AppSettings.setMonitoringEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, BatteryMonitorService::class.java)
        )
        assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
        assertFalse("watchdog must not report a restart for a live service", MonitorWatchdog.ensureMonitoring(context))
    }

    @Test
    fun tickWhileAlive_repostsTheCurrentNotification() {
        // Settings: single tier above the emulator battery, quiet cadence so the
        // notification shows the monitoring-tier state.
        AppSettings.setMonitoringEnabled(context, true)
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                    level = 90, name = "Battery low", intervalSec = 2, durationSec = 1,
                    lockScreen = false
                )
            )
        )
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set level 42")
        ContextCompat.startForegroundService(
            context,
            Intent(context, BatteryMonitorService::class.java)
        )
        assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue("no notification initially", await(10_000) { notificationFor(nm) != null })

        // A watchdog tick on a live service must restore a missing notification
        // (re-posted from the sticky battery by the ACTION_REFRESH handling).
        nm.cancel(BatteryMonitorService.NOTIFICATION_ID)
        MonitorWatchdog.ensureMonitoring(context)
        assertTrue(
            "tick did not re-post the notification",
            await(10_000) { notificationText(nm)?.contains("42") == true }
        )
    }

    @Test
    fun manifest_declaresExactAlarmPermissions() {
        // USE_EXACT_ALARM auto-grants exact alarms on API 33+ (Android 14+ does
        // not grant SCHEDULE_EXACT_ALARM by default); without them the watchdog
        // restart is not exempt from the background FGS-start restriction.
        @Suppress("DEPRECATION")
        val perms = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty()
        assertTrue("USE_EXACT_ALARM not declared", perms.contains("android.permission.USE_EXACT_ALARM"))
        assertTrue(
            "SCHEDULE_EXACT_ALARM not declared",
            perms.contains("android.permission.SCHEDULE_EXACT_ALARM")
        )
    }

    @Test
    fun chargingPause_keepsReArmingItsBoundedRecheck() {
        // Shrink the re-check delay so the test can observe several rounds.
        BatteryMonitorService.suppressedRechecks = 0
        BatteryMonitorService.suppressedRecheckMs = 1_000
        try {
            AppSettings.setMonitoringEnabled(context, true)
            AppSettings.saveThresholds(
                context,
                listOf(
                    AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                        level = 90, name = "Battery low", intervalSec = 2, durationSec = 1,
                        lockScreen = false, warnOnlyOnBattery = true
                    )
                )
            )
            shell("dumpsys battery set ac 1")
            shell("dumpsys battery set level 50")
            val before = BatteryMonitorService.bannerShows
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
            // Two or more re-checks prove the pause re-arms itself: a lost
            // unplug broadcast can no longer dead-end the monitoring.
            assertTrue(
                "suppressed re-checks must keep re-arming",
                await(10_000) { BatteryMonitorService.suppressedRechecks >= 2 }
            )
            assertTrue(
                "no banner may show during charging suppression",
                BatteryMonitorService.bannerShows == before
            )
        } finally {
            BatteryMonitorService.suppressedRecheckMs = 15 * 60_000L
            BatteryMonitorService.suppressedRechecks = 0
        }
    }

    @Test
    fun scheduleRespectsTheMonitoringFlag() {
        // Disabled: schedule is a no-op (never resurrects a deliberate Stop).
        AppSettings.setMonitoringEnabled(context, false)
        MonitorWatchdog.schedule(context)
        assertTrue("disabled: watchdog must not arm", MonitorWatchdog.lastArmedAtElapsedMs < 0)

        // Enabled: schedule arms a fresh pending alarm and records the stamp.
        AppSettings.setMonitoringEnabled(context, true)
        MonitorWatchdog.schedule(context)
        assertTrue("enabled: watchdog must arm", MonitorWatchdog.lastArmedAtElapsedMs >= 0)
    }

    @Test
    fun watchdogBroadcastReArmsThenRestartsTheDeadService() {
        // End-to-end for the receiver's crash-proof ordering: it re-arms the
        // next tick *before* acting, so a fresh alarm exists even if the
        // restart itself is interrupted, and the dead service comes back.
        AppSettings.setMonitoringEnabled(context, true)
        assertFalse("precondition: service not running", BatteryMonitorService.isRunning)

        context.sendBroadcast(
            Intent(context, WatchdogReceiver::class.java)
                .setAction(WatchdogReceiver.ACTION_WATCHDOG)
        )
        assertTrue("tick must re-arm the watchdog", await(10_000) { MonitorWatchdog.lastArmedAtElapsedMs >= 0 })
        assertTrue("service did not come back", await(10_000) { BatteryMonitorService.isRunning })
    }

    @Test
    fun launchResumesEnabledMonitoring() {
        // The system killed the service but the user still wants monitoring:
        // simply opening the app must restart it and re-arm the watchdog.
        AppSettings.setMonitoringEnabled(context, true)
        assertFalse("precondition: service dead", BatteryMonitorService.isRunning)

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "opening the app must resume monitoring",
                await(10_000) { BatteryMonitorService.isRunning }
            )
        }
        assertTrue("launch must re-arm the watchdog", MonitorWatchdog.lastArmedAtElapsedMs >= 0)
    }

    @Test
    fun launchStaysStoppedWhenDisabled() {
        // A deliberate Stop is recorded in the flag; opening the app must not
        // undo it (no restart, no re-arm).
        AppSettings.setMonitoringEnabled(context, false)

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1_000)
            assertFalse("deliberate Stop must not be undone", BatteryMonitorService.isRunning)
        }
        assertTrue("disabled: launch must not arm the watchdog", MonitorWatchdog.lastArmedAtElapsedMs < 0)
    }

    private fun notificationFor(nm: NotificationManager): Notification? =
        nm.activeNotifications.firstOrNull { it.id == BatteryMonitorService.NOTIFICATION_ID }?.notification

    private fun notificationText(nm: NotificationManager): String? =
        notificationFor(nm)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

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

    private fun grantNotificationPermission() {
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            // Already granted.
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
