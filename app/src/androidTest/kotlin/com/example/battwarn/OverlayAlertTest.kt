// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the overlay-based alert: the app declares SYSTEM_ALERT_WINDOW and,
 * once granted, the [OverlayAlert] banner renders and hides on demand.
 */
@RunWith(AndroidJUnit4::class)
class OverlayAlertTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun tearDown() {
        grantOverlay("default")
    }

    @Test
    fun manifest_declaresOverlayPermission() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        assertTrue(
            "SYSTEM_ALERT_WINDOW not declared",
            info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW")
        )
    }

    @Test
    fun showOverlay_whenPermitted_rendersAndHides() {
        grantOverlay("allow")
        assertTrue("overlay permission not granted", Settings.canDrawOverlays(context))

        val alert = OverlayAlert(context)
        instrumentation.runOnMainSync { alert.show("test banner", 60_000L) }
        assertTrue("overlay should be showing", alert.isShowing())

        instrumentation.runOnMainSync { alert.hide() }
        assertFalse("overlay should be hidden", alert.isShowing())
    }

    @Test
    fun autoDismiss_firesCallbackExactlyOnce() {
        grantOverlay("allow")
        assertTrue("overlay permission not granted", Settings.canDrawOverlays(context))

        var dismissCount = 0
        val alert = OverlayAlert(context)
        instrumentation.runOnMainSync {
            assertTrue(alert.show("auto dismiss", 300L) { dismissCount++ })
        }

        assertTrue("callback did not fire after auto-dismiss", await(5_000) { dismissCount == 1 })
        assertFalse("overlay should be gone", alert.isShowing())

        // A second hide() must not fire the callback again.
        instrumentation.runOnMainSync { alert.hide() }
        Thread.sleep(500)
        assertEquals("callback fired more than once", 1, dismissCount)
    }

    @Test
    fun showFails_withoutOverlayPermission_noCallback() {
        grantOverlay("deny")

        var dismissed = false
        val alert = OverlayAlert(context)
        instrumentation.runOnMainSync {
            val shown = alert.show("no permission", 300L) { dismissed = true }
            assertFalse("overlay must not render without permission", shown)
        }
        assertFalse("overlay should not be showing", alert.isShowing())
        assertFalse("callback must not run", dismissed)
    }

    /**
     * Sets the SYSTEM_ALERT_WINDOW appop for both the target package and the
     * instrumentation's op package (Settings.canDrawOverlays resolves the appop
     * via getOpPackageName(), which is the test package under instrumentation).
     */
    private fun grantOverlay(mode: String) {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
        val opPackage = context.opPackageName
        if (opPackage != context.packageName) {
            shell("appops set $opPackage SYSTEM_ALERT_WINDOW $mode")
        }
        if (mode == "allow") {
            // appops applies asynchronously; wait for it to land.
            await(5_000) { Settings.canDrawOverlays(context) }
        }
    }

    /** Runs a shell command and waits for it to finish (drains the output). */
    private fun shell(cmd: String) {
        val fd = instrumentation.uiAutomation.executeShellCommand(cmd)
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