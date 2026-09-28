// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.battwarn.databinding.ActivityLockScreenAlertBinding

/**
 * Full-screen low-battery warning drawn above the lock screen.
 *
 * The floating banner ([OverlayAlert]) uses a TYPE_APPLICATION_OVERLAY window,
 * which Android has drawn below the keyguard since API 29 — so it is invisible
 * while the device is locked. An Activity with [setShowWhenLocked] is the only
 * supported surface above the keyguard; this is the alarm-clock pattern.
 *
 * It is only launched while the display is already on (the screen is never
 * woken) and the device is locked. It stays up for the tier's duration, can be
 * dismissed with a tap, and reports its dismissal back to the service via
 * [ACTION_LOCK_ALERT_DISMISSED] so the quiet pause between alerts is measured
 * from dismissal exactly like the overlay banner.
 */
class LockScreenAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenAlertBinding
    private val handler = Handler(Looper.getMainLooper())
    private var dismissalReported = false

    private val dismissTask = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        binding = ActivityLockScreenAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnClickListener { finish() }
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        reportDismissal()
        super.onDestroy()
    }

    /** Shows the message and (re)arms the auto-dismiss timer. */
    private fun render(intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.alert_message_format, getString(R.string.alert_name_default), 0)
        val duration = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        val bgColor = intent?.getIntExtra(EXTRA_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        val fgColor = intent?.getIntExtra(EXTRA_FG_COLOR, DEFAULT_FG_COLOR) ?: DEFAULT_FG_COLOR
        binding.root.setBackgroundColor(bgColor)
        binding.lockAlertText.setTextColor(fgColor)
        binding.lockAlertText.text = message
        handler.removeCallbacks(dismissTask)
        handler.postDelayed(dismissTask, duration)
    }

    /** Fires exactly once, for both the auto-dismiss and the tap. */
    private fun reportDismissal() {
        if (dismissalReported) return
        dismissalReported = true
        sendBroadcast(
            Intent(ACTION_LOCK_ALERT_DISMISSED).setPackage(packageName)
        )
    }

    companion object {
        const val ACTION_LOCK_ALERT_DISMISSED = "com.example.battwarn.LOCK_ALERT_DISMISSED"
        const val EXTRA_MESSAGE = "com.example.battwarn.extra.MESSAGE"
        const val EXTRA_DURATION_MS = "com.example.battwarn.extra.DURATION_MS"
        const val EXTRA_BG_COLOR = "com.example.battwarn.extra.BG_COLOR"
        const val EXTRA_FG_COLOR = "com.example.battwarn.extra.FG_COLOR"
        const val DEFAULT_DURATION_MS = 5_000L
    }
}
