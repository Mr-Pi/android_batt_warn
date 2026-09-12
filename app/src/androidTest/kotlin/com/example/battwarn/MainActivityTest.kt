// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the main activity and checks the battery UI is populated, the
 * single Start/Stop toggle reflects the service state, the level updates
 * live while visible, and the alert sound can be silenced.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun launch_showsBatteryLevelAndToggle() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val battery = activity.findViewById<TextView>(R.id.battery_text)
                assertFalse(
                    "battery level not shown",
                    battery.text.isNullOrBlank() ||
                        battery.text == activity.getString(R.string.battery_placeholder)
                )

                val monitor = activity.findViewById<Button>(R.id.monitor_button)
                assertTrue("monitor toggle disabled", monitor.isEnabled)
                assertEquals(
                    "toggle must offer to start when the service is not running",
                    activity.getString(R.string.start_monitoring),
                    monitor.text.toString()
                )

                // Status bar icons must be readable on the bright surface:
                // dark icons in light mode, light icons in dark mode.
                val night = (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val controller = WindowInsetsControllerCompat(
                    activity.window, activity.findViewById(android.R.id.content)
                )
                assertEquals(
                    "status bar icon appearance must follow the theme",
                    !night,
                    controller.isAppearanceLightStatusBars
                )
            }
        }
    }

    @Test
    fun batteryLevelUpdatesLive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Lowering the battery emits the real ACTION_BATTERY_CHANGED
            // broadcast; the visible activity must pick it up live.
            shell("dumpsys battery set level 42")

            assertTrue("battery text did not update live", await(10_000) {
                textOf(scenario, R.id.battery_text) == "42%"
            })
        }
    }

    @Test
    fun removeSound_clearsUriAndShowsSilent() {
        AppSettings.prefs(context).edit()
            .putString(
                AppSettings.KEY_CRITICAL_SOUND_URI,
                "content://settings/system/notification_sound"
            )
            .commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val value = activity.findViewById<TextView>(R.id.sound_value)
                val remove = activity.findViewById<Button>(R.id.remove_sound_button)
                assertTrue("remove button should be enabled with a tone", remove.isEnabled)

                remove.performClick()

                assertEquals(
                    "remove must clear the stored tone",
                    "",
                    AppSettings.criticalSoundUri(context)
                )
                assertEquals(activity.getString(R.string.sound_silent), value.text.toString())
                assertFalse("remove must disable itself once silent", remove.isEnabled)
            }
        }
    }

    private fun textOf(scenario: ActivityScenario<MainActivity>, viewId: Int): String? {
        var text: String? = null
        scenario.onActivity { activity ->
            text = activity.findViewById<TextView>(viewId).text.toString()
        }
        return text
    }

    /** Runs a shell command and waits for it to finish (drains the output). */
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

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}