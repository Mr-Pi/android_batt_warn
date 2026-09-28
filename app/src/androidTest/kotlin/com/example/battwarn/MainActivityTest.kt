// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the main activity and checks the battery UI is populated, the
 * single Start/Stop toggle reflects the service state, the level updates
 * live while visible, and threshold rows can be loaded, added, and have
 * their per-threshold sound removed.
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
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(
                    soundUri = "content://settings/system/notification_sound"
                )
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = rowAt(activity, 0)
                val value = row.findViewById<TextView>(R.id.sound_value)
                val remove = row.findViewById<Button>(R.id.remove_sound_button)
                assertTrue("remove button should be enabled with a tone", remove.isEnabled)

                remove.performClick()

                assertEquals(activity.getString(R.string.sound_silent), value.text.toString())
                assertFalse("remove must disable itself once silent", remove.isEnabled)

                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertEquals(
            "remove must clear the stored tone after saving",
            "",
            AppSettings.thresholds(context).first().soundUri
        )
    }

    @Test
    fun addThreshold_addsRowAndPersists() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
                val before = container.childCount

                activity.findViewById<Button>(R.id.add_threshold_button).performClick()
                assertEquals("add must append a row", before + 1, container.childCount)

                // Give the new row a distinct level and save.
                val row = rowAt(activity, before)
                row.findViewById<TextInputEditText>(R.id.level_field).setText("25")
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertTrue(
            "new threshold must persist after saving",
            AppSettings.thresholds(context).any { it.level == 25 }
        )
    }

    @Test
    fun save_sortsRowsDescending() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Start from the 20/9 baseline; make the visible order
                // deliberately unsorted: 10 on top, then a new 30 row.
                val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
                val before = container.childCount
                rowAt(activity, 0).findViewById<TextInputEditText>(R.id.level_field)
                    .setText("10")
                activity.findViewById<Button>(R.id.add_threshold_button).performClick()
                rowAt(activity, before).findViewById<TextInputEditText>(R.id.level_field)
                    .setText("30")
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }

        // Reopening shows the stored order; assert the sorted levels match.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
                val levels = (0 until container.childCount).map { index ->
                    rowAt(activity, index)
                        .findViewById<TextInputEditText>(R.id.level_field)
                        .text.toString().toInt()
                }
                assertEquals(
                    "saved list must be sorted by level descending",
                    listOf(30, 10, 9),
                    levels
                )
            }
        }
    }

    @Test
    fun rows_loadSavedThresholds() {
        AppSettings.saveThresholds(
            context,
            listOf(threshold(30), threshold(10))
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
                assertEquals("saved thresholds must render as rows", 2, container.childCount)
                assertEquals(
                    "30",
                    rowAt(activity, 0).findViewById<TextInputEditText>(R.id.level_field).text.toString()
                )
                assertEquals(
                    "10",
                    rowAt(activity, 1).findViewById<TextInputEditText>(R.id.level_field).text.toString()
                )
            }
        }
    }

    @Test
    fun fields_roundTripThroughSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = rowAt(activity, 0)
                row.findViewById<TextInputEditText>(R.id.name_field).setText("Urgent")
                row.findViewById<TextInputEditText>(R.id.interval_field).setText("42")
                row.findViewById<TextInputEditText>(R.id.duration_field).setText("7")
                row.findViewById<MaterialCheckBox>(R.id.warn_on_battery_check).performClick()
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        val stored = AppSettings.thresholds(context).first()
        assertEquals("edited name must persist", "Urgent", stored.name)
        assertEquals("edited interval must persist", 42, stored.intervalSec)
        assertEquals("edited duration must persist", 7, stored.durationSec)
        assertFalse("edited warn-only flag must persist", stored.warnOnlyOnBattery)
    }

    @Test
    fun preview_textMatchesRowNameAndLevel() {
        AppSettings.saveThresholds(
            context,
            listOf(
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 30, name = "Urgent"),
                AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = 10, name = "Low"),
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preview0 = rowAt(activity, 0).findViewById<TextView>(R.id.preview_text)
                val preview1 = rowAt(activity, 1).findViewById<TextView>(R.id.preview_text)
                assertEquals(
                    "sample must use the row's name and level",
                    "Urgent: 30%",
                    preview0.text.toString()
                )
                assertEquals(
                    "sample must use the row's name and level",
                    "Low: 10%",
                    preview1.text.toString()
                )

                // Editing the name or level must re-render the sample live.
                val name = rowAt(activity, 0).findViewById<TextInputEditText>(R.id.name_field)
                name.setText("Critical")
                assertEquals(
                    "sample must follow the edited name",
                    "Critical: 30%",
                    preview0.text.toString()
                )
                rowAt(activity, 0).findViewById<TextInputEditText>(R.id.level_field)
                    .setText("25")
                assertEquals(
                    "sample must follow the edited level",
                    "Critical: 25%",
                    preview0.text.toString()
                )
            }
        }
    }

    @Test
    fun preview_reflectsPickedColorsAfterReopen() {
        // Pick a non-default background and text on the top row via the color
        // picker dialogs, then save.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                rowAt(activity, 0).findViewById<View>(R.id.bg_color_swatch).performClick()
            }
            pickColorFromDialog(scenario, 5) // blue background
            scenario.onActivity { activity ->
                rowAt(activity, 0).findViewById<View>(R.id.fg_color_swatch).performClick()
            }
            pickColorFromDialog(scenario, 10) // black text
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }

        // Reopen the settings: the sample banner must keep the picked colors
        // instead of falling back to the default white-on-red, and the
        // side-by-side preview swatches must show them.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = rowAt(activity, 0)
                val preview = row.findViewById<TextView>(R.id.preview_text)
                assertEquals(
                    "sample background must survive a reopen",
                    ALERT_PALETTE[5],
                    preview.backgroundTintList!!.defaultColor
                )
                assertEquals(
                    "sample text must survive a reopen",
                    ALERT_PALETTE[10],
                    preview.currentTextColor
                )

                val bgSwatch = row.findViewById<View>(R.id.bg_color_swatch)
                val fgSwatch = row.findViewById<View>(R.id.fg_color_swatch)
                assertEquals(
                    "bg preview swatch must show the picked color",
                    ALERT_PALETTE[5],
                    (bgSwatch.background as GradientDrawable).color!!.defaultColor
                )
                assertEquals(
                    "fg preview swatch must show the picked color",
                    ALERT_PALETTE[10],
                    (fgSwatch.background as GradientDrawable).color!!.defaultColor
                )
            }
        }
    }

    @Test
    fun emptyFields_keepLabelAndPlaceholderSeparate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val level = activity.findViewById<TextInputLayout>(R.id.level_layout)
                val name = activity.findViewById<TextInputLayout>(R.id.name_layout)

                // The label must never collapse into the field, otherwise it
                // would stack on the placeholder shown in an empty input.
                assertFalse("label must stay floated", level.isExpandedHintEnabled)
                assertFalse("label must stay floated", name.isExpandedHintEnabled)

                // The layout keeps the label; the placeholder is the only
                // in-field text while the input is empty.
                assertEquals(
                    activity.getString(R.string.label_level),
                    level.hint.toString()
                )
                assertEquals(
                    "placeholder must be the in-field text when empty",
                    activity.getString(R.string.placeholder_percent),
                    level.placeholderText.toString()
                )
                assertEquals(
                    "placeholder must be the in-field text when empty",
                    activity.getString(R.string.placeholder_name),
                    name.placeholderText.toString()
                )

                // The edit text must not draw a second hint on top of the
                // placeholder (that caused the overlap on empty fields).
                val field = level.findViewById<TextInputEditText>(R.id.level_field)
                assertNotEquals(
                    "edit text hint must not duplicate the placeholder",
                    activity.getString(R.string.placeholder_percent),
                    field.hint?.toString()
                )
            }
        }
    }

    private fun threshold(level: Int): AlertThreshold =
        AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = level)

    /** Taps the palette swatch at [index] inside the open color dialog. */
    private fun pickColorFromDialog(scenario: ActivityScenario<MainActivity>, index: Int) {
        scenario.onActivity { activity ->
            val dialog = activity.currentColorDialog
                ?: throw AssertionError("color picker dialog not shown")
            val grid = dialog.findViewById<GridLayout>(R.id.color_picker_grid)!!
            assertTrue(
                "picker grid must hold all palette swatches",
                grid.childCount == ALERT_PALETTE.size
            )
            grid.getChildAt(index).performClick()
        }
    }

    private fun rowAt(activity: MainActivity, index: Int): View {
        val container = activity.findViewById<ViewGroup>(R.id.thresholds_container)
        return container.getChildAt(index)
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