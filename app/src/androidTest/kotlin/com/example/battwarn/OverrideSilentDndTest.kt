// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the "Play critical sound in silent / Do Not Disturb" option: the
 * default stays off, the override gate permits playback in silent/vibrate,
 * the alarm-stream attributes break the silent ringer, and the checkbox
 * round-trips through the settings UI.
 */
@RunWith(AndroidJUnit4::class)
class OverrideSilentDndTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        shell("dumpsys battery reset")
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun default_isOff() {
        assertFalse(
            "default must preserve the honoring-silent behavior",
            AppSettings.overrideSilentDnd(context)
        )
    }

    @Test
    fun gate_blocksSilentOrVibrateWithoutOverride() {
        assertFalse(shouldPlayCriticalSound(false, AudioManager.RINGER_MODE_SILENT))
        assertFalse(shouldPlayCriticalSound(false, AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(shouldPlayCriticalSound(false, AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun gate_allowsSilentOrVibrateWithOverride() {
        assertTrue(shouldPlayCriticalSound(true, AudioManager.RINGER_MODE_SILENT))
        assertTrue(shouldPlayCriticalSound(true, AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(shouldPlayCriticalSound(true, AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun override_rendersAlarmAudioAttributes() {
        val attrs = criticalSoundAlarmAttributes()
        assertEquals(
            "override sound must use the alarm stream to pierce the silent ringer",
            AudioAttributes.USAGE_ALARM,
            attrs.usage
        )
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attrs.contentType)
    }

    @Test
    fun checkbox_roundTripsThroughSave() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = activity.findViewById<MaterialCheckBox>(R.id.override_silent_check)
                assertFalse("must start unchecked with the setting off", check.isChecked)

                check.performClick()
                activity.findViewById<Button>(R.id.save_button).performClick()
            }
        }
        assertTrue(
            "saving must persist the checked override",
            AppSettings.overrideSilentDnd(context)
        )
    }

    @Test
    fun checkbox_loadsSavedValue() {
        AppSettings.prefs(context).edit()
            .putBoolean(AppSettings.KEY_OVERRIDE_SILENT_DND, true)
            .commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val check = activity.findViewById<MaterialCheckBox>(R.id.override_silent_check)
                assertTrue("saved override must be reflected on load", check.isChecked)
            }
        }
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
}