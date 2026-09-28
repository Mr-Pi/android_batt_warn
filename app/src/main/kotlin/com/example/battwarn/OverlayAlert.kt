// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Draws a toast-like banner above other apps, so the alert is visible even
 * while the app is backgrounded. Requires the SYSTEM_ALERT_WINDOW
 * ("Display over other apps") permission — the caller must check
 * [android.provider.Settings.canDrawOverlays] first.
 */
class OverlayAlert(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: View? = null
    private var dismissedCallback: (() -> Unit)? = null
    private val dismissTask = Runnable { hide() }

    /**
     * Shows the banner for [durationMs], then removes it. Tap dismisses early.
     * [bgColor]/[fgColor] tint the banner surface (the rounded background and
     * the text) per the active threshold. [onDismissed] (set the next time the
     * banner closes, whether that is the auto-dismiss or the tap) is invoked
     * on the main looper exactly once. Returns false when the overlay could
     * not be added (e.g. permission revoked) — no callback will fire in that case.
     */
    fun show(
        message: CharSequence,
        durationMs: Long,
        bgColor: Int = DEFAULT_BG_COLOR,
        fgColor: Int = DEFAULT_FG_COLOR,
        onDismissed: (() -> Unit)? = null
    ): Boolean {
        hide()

        val v = LayoutInflater.from(appContext).inflate(R.layout.overlay_banner, null)
        val text = v.findViewById<TextView>(R.id.overlay_banner_text)
        text.text = message
        // Tinting the rounded shape drawable keeps the corners while swapping
        // the threshold's background color.
        text.backgroundTintList = ColorStateList.valueOf(bgColor)
        text.setTextColor(fgColor)
        v.setOnClickListener { hide() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        return try {
            windowManager.addView(v, params)
            view = v
            dismissedCallback = onDismissed
            handler.postDelayed(dismissTask, durationMs)
            true
        } catch (e: Exception) {
            // Overlay permission revoked or window rejected — notification is the fallback.
            view = null
            false
        }
    }

    fun hide() {
        handler.removeCallbacks(dismissTask)
        val closing = view
        view = null
        closing?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already removed.
            }
            // Fires for the auto-dismiss or the tap, but only the closing call
            // reaches this path, so the callback never runs twice.
            dismissedCallback?.invoke()
            dismissedCallback = null
        }
    }

    fun isShowing(): Boolean = view != null
}