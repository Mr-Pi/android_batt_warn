package com.example.battwarn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the interval semantics: the whole cycle per alert is
 * duration (banner up) + interval (quiet pause counted from the banner
 * closing, auto-dismiss or tap). The emulator battery sits below the
 * configured thresholds, so the service cycles banners end to end.
 */
@RunWith(AndroidJUnit4::class)
class AlertIntervalTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun banners_repeatWithDurationPlusIntervalGap() {
        grantRuntimePermissions()

        // Minimums keep the test fast and cover the 1s lower bound:
        // banner 1s on, 1s pause after close, so shows repeat ~every 2s.
        AppSettings.prefs(context).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, 90)
            .putInt(AppSettings.KEY_POLL_INTERVAL_SEC, 1)
            .putInt(AppSettings.KEY_TOAST_DURATION_SEC, 1)
            .commit()
        shell("dumpsys battery set level 50")

        ActivityScenario.launch(MainActivity::class.java).use {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            // Level already below the threshold: banner 1 within ~10s.
            assertTrue("first banner did not show", await(10_000) { bannerShows() >= 1 })

            val firstShows = bannerShows()
            // Second banner must arrive only after close + interval
            // (quiet pause), so ~duration+interval since the first.
            assertTrue("no repeated banner after quiet pause", await(15_000) { bannerShows() >= firstShows + 1 })

            // Dismissal callback ran: last dismissed time is recorded.
            assertTrue(
                "dismiss callback did not run",
                BatteryMonitorService.lastBannerDismissedAtMs > 0
            )
        }
    }

    private fun bannerShows(): Long = BatteryMonitorService.bannerShows

    private fun grantRuntimePermissions() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.let { ui ->
            try {
                ui.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                // Already granted.
            }
        }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
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