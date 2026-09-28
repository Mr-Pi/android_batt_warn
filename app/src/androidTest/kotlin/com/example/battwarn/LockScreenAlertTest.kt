// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.checkbox.MaterialCheckBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the per-threshold "Show on lock screen" option: it defaults on, the
 * gate only fires for a locked device with the display already on and the
 * overlay permission granted, and the full-screen [LockScreenAlertActivity]
 * renders the message with the threshold's colors and auto-dismisses.
 *
 * The actual keyguard overlay is a platform behavior (an Activity with
 * setShowWhenLocked, permitted by SYSTEM_ALERT_WINDOW) rather than app logic,
 * so it is verified manually on the emulator; here we pin the decision logic
 * and the activity's rendering/lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class LockScreenAlertTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun default_isOn() {
        assertTrue(
            "lock-screen alert must be enabled by default",
            AppSettings.DEFAULT_THRESHOLDS.all { it.lockScreen }
        )
    }

    @Test
    fun gate_requiresSettingLockScreenAndOverlay() {
        // Happy path: on, locked, screen on, overlay granted.
        assertTrue(shouldShowOnLockScreen(true, true, true, true))
    }

    @Test
    fun gate_blocksEachMissingCondition() {
        assertFalse("setting off", shouldShowOnLockScreen(true, true, false, true))
        assertFalse("not locked", shouldShowOnLockScreen(false, true, true, true))
        assertFalse("screen off (must not wake)", shouldShowOnLockScreen(true, false, true, true))
        assertFalse("no overlay permission", shouldShowOnLockScreen(true, true, true, false))
    }

    @Test
    fun activity_rendersMessageAndColors() {
        val intent = Intent(context, LockScreenAlertActivity::class.java)
            .putExtra(LockScreenAlertActivity.EXTRA_MESSAGE, "Battery low: 3%")
            .putExtra(LockScreenAlertActivity.EXTRA_DURATION_MS, 60_000L)
            .putExtra(LockScreenAlertActivity.EXTRA_BG_COLOR, 0xFF0000FF.toInt())
            .putExtra(LockScreenAlertActivity.EXTRA_FG_COLOR, 0xFFFFFF00.toInt())

        ActivityScenario.launch<LockScreenAlertActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "Battery low: 3%",
                    activity.findViewById<TextView>(R.id.lock_alert_text).text.toString()
                )
                val bg = (activity.findViewById<View>(R.id.lock_screen_root).background as ColorDrawable).color
                assertEquals("background must use the threshold color", 0xFF0000FF.toInt(), bg)
                assertEquals(
                    "text must use the threshold color",
                    0xFFFFFF00.toInt(),
                    activity.findViewById<TextView>(R.id.lock_alert_text).currentTextColor
                )
            }
        }
    }

    @Test
    fun activity_autoFinishesAfterDuration() {
        val intent = Intent(context, LockScreenAlertActivity::class.java)
            .putExtra(LockScreenAlertActivity.EXTRA_MESSAGE, "Battery low: 8%")
            .putExtra(LockScreenAlertActivity.EXTRA_DURATION_MS, 400L)

        ActivityScenario.launch<LockScreenAlertActivity>(intent).use { scenario ->
            assertTrue(
                "activity did not auto-finish",
                await(5_000) { scenario.state == Lifecycle.State.DESTROYED }
            )
        }
    }

    @Test
    fun checkbox_roundTripsThroughSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0).findViewById<MaterialCheckBox>(R.id.lock_screen_check)
                assertTrue("must start checked with the setting on by default", check.isChecked)

                check.performClick()
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertFalse(
            "saving must persist the unchecked lock-screen setting",
            AppSettings.thresholds(context).first().lockScreen
        )
    }

    @Test
    fun checkbox_loadsSavedValue() {
        AppSettings.saveThresholds(
            context,
            listOf(AppSettings.DEFAULT_NEW_THRESHOLD.copy(lockScreen = false))
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = rowAt(activity, 0).findViewById<MaterialCheckBox>(R.id.lock_screen_check)
                assertFalse("saved (off) value must be reflected on load", check.isChecked)
            }
        }
    }

    private fun rowAt(activity: MainActivity, index: Int): View {
        val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
        return container.getChildAt(index)
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}