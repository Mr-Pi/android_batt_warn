package com.example.battwarn

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the main activity and checks the battery UI is populated and the
 * Start/Stop controls are usable.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun launch_showsBatteryLevelAndControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val battery = activity.findViewById<TextView>(R.id.battery_text)
                assertFalse(
                    "battery level not shown",
                    battery.text.isNullOrBlank() || battery.text == activity.getString(R.string.battery_placeholder)
                )

                val start = activity.findViewById<Button>(R.id.start_button)
                val stop = activity.findViewById<Button>(R.id.stop_button)
                assertTrue("start button disabled", start.isEnabled)
                assertTrue("stop button disabled", stop.isEnabled)
            }
        }
    }
}