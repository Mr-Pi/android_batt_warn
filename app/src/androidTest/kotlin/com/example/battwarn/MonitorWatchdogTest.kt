// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
 * watchdog leaves it stopped.
 */
@RunWith(AndroidJUnit4::class)
class MonitorWatchdogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun reset() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        await(8_000L) { !BatteryMonitorService.isRunning }
        grantNotificationPermission()
    }

    @After
    fun tearDown() {
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            await(8_000L) { !BatteryMonitorService.isRunning }
        }
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
    fun tick_doesNothingWhileTheServiceIsAlive() {
        AppSettings.setMonitoringEnabled(context, true)
        ContextCompat.startForegroundService(
            context,
            Intent(context, BatteryMonitorService::class.java)
        )
        assertTrue("service did not start", await(10_000) { BatteryMonitorService.isRunning })
        assertFalse("watchdog must not restart a live service", MonitorWatchdog.ensureMonitoring(context))
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
