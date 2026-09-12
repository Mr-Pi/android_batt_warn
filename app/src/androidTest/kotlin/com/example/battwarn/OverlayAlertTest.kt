package com.example.battwarn

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
    }

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).close()
    }
}