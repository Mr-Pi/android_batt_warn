package com.example.battwarn

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the main activity and checks the battery UI is populated and the
 * single Start/Stop toggle reflects the service state.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

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
            }
        }
    }

    @Test
    fun removeSound_clearsUriAndShowsSilent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
                assertEquals(
                    activity.getString(R.string.sound_silent),
                    value.text.toString()
                )
                assertFalse("remove must disable itself once silent", remove.isEnabled)
            }
        }
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings.prefs(context).edit().clear().commit()
    }
}