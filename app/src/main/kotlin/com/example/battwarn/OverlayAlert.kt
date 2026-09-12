package com.example.battwarn

import android.content.Context
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
    private val dismissTask = Runnable { hide() }

    /** Shows the banner for [durationMs], then removes it. Tap dismisses early. */
    fun show(message: CharSequence, durationMs: Long) {
        hide()

        val v = LayoutInflater.from(appContext).inflate(R.layout.overlay_banner, null)
        v.findViewById<TextView>(R.id.overlay_banner_text).text = message
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

        try {
            windowManager.addView(v, params)
            view = v
            handler.postDelayed(dismissTask, durationMs)
        } catch (e: Exception) {
            // Overlay permission revoked or window rejected — notification is the fallback.
            view = null
        }
    }

    fun hide() {
        handler.removeCallbacks(dismissTask)
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        view = null
    }

    fun isShowing(): Boolean = view != null
}