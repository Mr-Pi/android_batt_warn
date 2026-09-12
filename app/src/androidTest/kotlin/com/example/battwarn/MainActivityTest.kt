package com.example.battwarn

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}