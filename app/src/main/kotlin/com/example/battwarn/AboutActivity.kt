// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.battwarn.databinding.ActivityAboutBinding

/**
 * In-app legal screen. The license texts (MIT, Apache-2.0) and the
 * third-party notices are generated at build time from the repo's
 * LICENSE / NOTICE / LICENSES files into raw resources — there is no
 * second copy of that content in the source tree.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutVersion.text = getString(
            R.string.about_version,
            versionName()
        )
        binding.privacyText.text = getString(R.string.privacy_text)
        binding.licenseText.text = rawText(R.raw.mit_license)
        binding.thirdPartyText.text = rawText(R.raw.third_party_notices)
        binding.apacheText.text = rawText(R.raw.apache_license_2_0)
    }

    /** Human-readable version name (falls back to "unknown"). */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    /** Reads a generated raw license/notice resource as text. */
    private fun rawText(rawId: Int): String =
        resources.openRawResource(rawId).bufferedReader().use { it.readText() }
}
