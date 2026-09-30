// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the import/export dialog: it shows the stored config JSON, Copy puts
 * it on the clipboard, a valid paste loads the rows (and Save persists them),
 * and an invalid paste shows an error without clobbering the rows.
 */
@RunWith(AndroidJUnit4::class)
class ImportExportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun stopLeftoverService() {
        // MY_PACKAGE_REPLACED / a previous test can leave the service running;
        // stop it so the activity sees "Start monitoring" (isRunning is global).
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        await(8_000L) { !BatteryMonitorService.isRunning }
    }

    @After
    fun tearDown() {
        if (await(8_000L) { BatteryMonitorService.isRunning }) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            await(8_000L) { !BatteryMonitorService.isRunning }
        }
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun dialog_showsStoredJson() {
        val stored = listOf(threshold(90, "Ninety"), threshold(50, "Fifty"))
        AppSettings.saveThresholds(context, stored)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.import_export_button).performClick()
                val field = fieldOf(activity)
                assertEquals(stored, decodeThresholds(field))
            }
        }
    }

    @Test
    fun import_validJson_loadsRowsAndSaves() {
        AppSettings.saveThresholds(context, listOf(threshold(20, "Old")))
        val imported = listOf(threshold(80, "Imported high"), threshold(15, "Imported low"))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.import_export_button).performClick()
                // Paste the imported config into the dialog field.
                activity.currentJsonDialog!!.let { dialog ->
                    dialogField(dialog).setText(encodeThresholds(imported))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                // Rows must now hold the imported tiers.
                assertEquals(imported, rowsFrom(activity))
                activity.findViewById<android.view.View>(R.id.save_button).performClick()
            }
        }

        assertEquals(imported, AppSettings.thresholds(context))
    }

    @Test
    fun import_garbage_showsErrorAndKeepsRows() {
        val stored = listOf(threshold(20, "Baseline"))
        AppSettings.saveThresholds(context, stored)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.import_export_button).performClick()
                activity.currentJsonDialog!!.let { dialog ->
                    dialogField(dialog).setText("not json at all")
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    // Still open, with an error, and the rows untouched.
                    assertNotNull("dialog must stay open on invalid import", activity.currentJsonDialog)
                    assertNotNull("invalid import must show an error", errorOf(dialog))
                    assertEquals(stored, rowsFrom(activity))
                }
            }
        }

        // Nothing was persisted over the stored config.
        assertEquals(stored, AppSettings.thresholds(context))
    }

    @Test
    fun copy_putsStoredJsonOnClipboard() {
        val stored = listOf(threshold(70, "Seventy"))
        AppSettings.saveThresholds(context, stored)

        var copied: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.import_export_button).performClick()
                activity.currentJsonDialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                copied = activity.lastExportedJson
            }
        }

        // The platform hides the clipboard from non-focused apps, so assert on
        // the exact payload Copy handed to the ClipboardManager.
        assertEquals(stored, decodeThresholds(copied))
    }

    private fun threshold(level: Int, name: String): AlertThreshold =
        AppSettings.DEFAULT_NEW_THRESHOLD.copy(level = level, name = name)

    private fun fieldOf(activity: MainActivity): String =
        dialogField(activity.currentJsonDialog!!).text.toString()

    private fun dialogField(dialog: AlertDialog) =
        dialog.findViewById<android.widget.EditText>(R.id.import_export_field)!!

    private fun errorOf(dialog: AlertDialog): CharSequence? =
        dialog.findViewById<TextInputLayout>(R.id.import_export_layout)?.error

    /** Reads the in-memory rows back as thresholds (unsaved edits included). */
    private fun rowsFrom(activity: MainActivity): List<AlertThreshold> {
        val container = activity.findViewById<android.view.ViewGroup>(R.id.thresholds_container)
        return (0 until container.childCount).map { index ->
            val row = container.getChildAt(index)
            AlertThreshold(
                level = row.findViewById<android.widget.EditText>(R.id.level_field)
                    .text.toString().toInt(),
                name = row.findViewById<android.widget.EditText>(R.id.name_field)
                    .text.toString(),
                intervalSec = row.findViewById<android.widget.EditText>(R.id.interval_field)
                    .text.toString().toInt(),
                durationSec = row.findViewById<android.widget.EditText>(R.id.duration_field)
                    .text.toString().toInt(),
                soundUri = "",
                lockScreen = true,
                overrideSilentDnd = false,
                bgColor = DEFAULT_BG_COLOR,
                fgColor = DEFAULT_FG_COLOR,
            )
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
