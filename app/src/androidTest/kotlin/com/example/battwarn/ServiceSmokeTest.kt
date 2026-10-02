// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        // Ensure startForeground already ran (isRunning flips after it) to
        // avoid the system's "did not call startForeground in time" crash.
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
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

    /**
     * The ongoing notification must carry a content intent that opens
     * MainActivity when tapped. The tap is simulated by sending the intent with
     * the app in the background; the SYSTEM_ALERT_WINDOW appop is granted first
     * because it is what exempts a background launch (Android 14+ restriction).
     */
    @Test
    fun notificationTapOpensMainActivity() {
        grantNotificationPermission()
        // Background-activity-launch exemption the real tap also relies on.
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")

        val contentIntent = ActivityScenario.launch(MainActivity::class.java).use {
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
            val sbn = nm.activeNotifications.first { it.packageName == context.packageName }
            assertNotNull("notification carries no content intent", sbn.notification.contentIntent)
            sbn.notification.contentIntent!!
        }

        val resumed = AtomicBoolean(false)
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) resumed.set(true)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        val app = context as Application
        app.registerActivityLifecycleCallbacks(observer)
        try {
            contentIntent.send()
            assertTrue(
                "sending the notification content intent did not open MainActivity",
                await(15_000) { resumed.get() }
            )
        } catch (e: Exception) {
            fail("content intent send failed: $e")
        } finally {
            app.unregisterActivityLifecycleCallbacks(observer)
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

        // Ensure startForeground already ran (isRunning flips after it) to
        // avoid the system's "did not call startForeground in time" crash.
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
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

    /** Runs a shell command via uiAutomation and waits for it to finish. */
    private fun shell(cmd: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val buffer = ByteArray(4096)
            while (input.read(buffer) != -1) {
                // Drain until the command completes.
            }
        }
    }
}