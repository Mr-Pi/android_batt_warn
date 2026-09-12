package com.example.battwarn

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for the foreground service: it must become `isRunning`, post
 * its fallback notification, and clear the flag when stopped.
 *
 * Note: the notification is only actually posted when the app is visible.
 * Headless instrumentation keeps the app invisible, so Android defers the
 * FGS notification; we keep MainActivity resumed first to avoid that.
 */
@RunWith(AndroidJUnit4::class)
class ServiceSmokeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
    }

    @Test
    fun startService_flipsRunningFlagAndPostsNotification() {
        grantNotificationPermission()

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )

            assertTrue("service did not set isRunning", await(10_000) { BatteryMonitorService.isRunning })

            val nm = context.getSystemService(NotificationManager::class.java)
            assertTrue(
                "service posted no startForeground notification",
                await(10_000) { nm.activeNotifications.any { it.packageName == context.packageName } }
            )
        }
    }

    @Test
    fun stopService_clearsRunningFlag() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, BatteryMonitorService::class.java)
        )
        await(10_000) { BatteryMonitorService.isRunning }
        assertTrue("service did not set isRunning", BatteryMonitorService.isRunning)

        context.stopService(Intent(context, BatteryMonitorService::class.java))
        assertTrue(
            "service still reports running after stop",
            await(10_000) { !BatteryMonitorService.isRunning }
        )
        assertFalse("service still reports running after stop", BatteryMonitorService.isRunning)
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }

    /** uiAutomation can grant runtime permissions to the app under test. */
    private fun grantNotificationPermission() {
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            // Already granted or denied by policy; the assertion below will catch problems.
        }
    }
}