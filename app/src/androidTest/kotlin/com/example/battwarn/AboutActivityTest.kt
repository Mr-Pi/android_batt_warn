// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the in-app legal screen renders the license content that is
 * generated at build time from the repo's LICENSE / NOTICE /
 * LICENSES/Apache-2.0.txt files.
 */
@RunWith(AndroidJUnit4::class)
class AboutActivityTest {

    @Test
    fun launch_showsVersionPrivacyAndLicenses() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val version = textOf(activity, R.id.about_version)
                assertTrue(
                    "version line missing",
                    version.contains("1.0")
                )

                val license = textOf(activity, R.id.license_text)
                assertTrue(
                    "MIT license text missing",
                    license.contains("MIT License") &&
                        license.contains("Permission is hereby granted")
                )
                assertTrue(
                    "MIT copyright line missing",
                    license.contains("Copyright (c) 2026 Batt Warn authors")
                )

                val thirdParty = textOf(activity, R.id.third_party_text)
                assertTrue(
                    "third-party notices missing",
                    thirdParty.contains("AndroidX") &&
                        thirdParty.contains("Apache License, Version 2.0")
                )

                val apache = textOf(activity, R.id.apache_text)
                assertTrue(
                    "Apache-2.0 license text missing",
                    apache.contains("Apache License") &&
                        apache.contains("Version 2.0, January 2004") &&
                        apache.contains("TERMS AND CONDITIONS")
                )
            }
        }
    }

    private fun textOf(activity: AboutActivity, viewId: Int): String {
        var text = ""
        activity.findViewById<TextView>(viewId)?.let { text = it.text.toString() }
        return text
    }
}
