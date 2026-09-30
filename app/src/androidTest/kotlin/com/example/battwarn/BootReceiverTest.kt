// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the autostart contract:
 *  - the manifest declares RECEIVE_BOOT_COMPLETED and registers BootReceiver
 *    for BOOT_COMPLETED / MY_PACKAGE_REPLACED, and
 *  - delivering either broadcast to [BootReceiver] actually starts the
 *    foreground service.
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun stopLeftoverService() {
        // isRunning is a process-wide static (no test orchestrator), so a
        // service started by a previous test can still be alive here. Stop it
        // and wait until it is really gone, otherwise the next start races it.
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        await(8_000L) { !BatteryMonitorService.isRunning }
        // The receiver honors the persisted user intent; most tests want it on.
        AppSettings.setMonitoringEnabled(context, true)
    }

    @After
    fun tearDown() {
        // Ensure startForeground already ran (isRunning flips after it) to
        // avoid the system's "did not call startForeground in time" crash,
        // then stop and wait for the service to be gone.
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            await(8_000L) { !BatteryMonitorService.isRunning }
        }
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun manifest_declaresAutostartContract() {
        @Suppress("DEPRECATION")
        val appInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS
        )

        val permissions = appInfo.requestedPermissions.orEmpty()
        assertTrue(
            "RECEIVE_BOOT_COMPLETED not declared",
            permissions.contains("android.permission.RECEIVE_BOOT_COMPLETED")
        )

        val receiverName = "${context.packageName}.BootReceiver"
        @Suppress("DEPRECATION")
        val receiver = appInfo.receivers.orEmpty()
            .firstOrNull { it.name == receiverName }
        assertNotNull("BootReceiver not registered in manifest", receiver)
    }

    @Test
    fun bootCompleted_resolvesToBootReceiver() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val intent = Intent(action).apply { setPackage(context.packageName) }
            val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)
            assertTrue(
                "no receiver resolves $action",
                resolved.any { it.activityInfo?.name == "${context.packageName}.BootReceiver" }
            )
        }
    }

    @Test
    fun bootCompleted_startsForegroundService() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue("service did not start on BOOT_COMPLETED", awaitRunning(10_000))
    }

    @Test
    fun packageReplaced_startsForegroundService() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue("service did not start on MY_PACKAGE_REPLACED", awaitRunning(10_000))
    }

    @Test
    fun otherActions_ignoreBroadcast() {
        BootReceiver().onReceive(context, Intent("com.example.UNRELATED"))
        Thread.sleep(500)
        assertTrue("service must not run for unrelated action", !BatteryMonitorService.isRunning)
    }

    @Test
    fun bootCompleted_doesNotResurrectAStoppedMonitor() {
        AppSettings.setMonitoringEnabled(context, false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(1000)
        assertTrue(
            "boot must not start monitoring the user stopped",
            !BatteryMonitorService.isRunning
        )
    }

    private fun awaitRunning(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (BatteryMonitorService.isRunning) return true
            Thread.sleep(200)
        }
        return false
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