package com.example.battwarn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Battery-respecting foreground service.
 *
 * IDLE mode (battery >= alert threshold): no polling at all. We wait for the
 * system's ACTION_BATTERY_LOW broadcast (fired ~15%, device dependent) or for
 * the service to start while the level is already below the alert threshold.
 *
 * ACTIVE mode (battery below the alert threshold): poll every configured
 * interval and show a toast. The FGS notification text is updated as a
 * fallback in case toasts are blocked in the background (Android 10+
 * restriction). Polling stops when the battery rises back above the alert
 * threshold or on ACTION_BATTERY_OKAY.
 *
 * All thresholds/timings are read from SharedPreferences (see [AppSettings]).
 */
class BatteryMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val batteryActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> startPolling()
                Intent.ACTION_BATTERY_OKAY -> stopPolling()
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            val level = currentBatteryLevel()
            val threshold = AppSettings.alertThreshold(this@BatteryMonitorService)
            updateNotification(level, threshold)
            if (level >= 0 && level < threshold) {
                showLowBatteryToast(level)
                handler.postDelayed(
                    this,
                    AppSettings.pollIntervalMillis(this@BatteryMonitorService)
                )
            } else {
                stopPolling()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val threshold = AppSettings.alertThreshold(this)
        val level = currentBatteryLevel()
        startForeground(NOTIFICATION_ID, buildNotification(level, threshold))
        registerBatteryActions()
        if (level in 0 until threshold) {
            startPolling()
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopPolling()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterReceiver(batteryActionReceiver)
        } else {
            @Suppress("DEPRECATION")
            unregisterReceiver(batteryActionReceiver)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.removeCallbacksAndMessages(null)
        handler.post(pollTask)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun registerBatteryActions() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryActionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryActionReceiver, filter)
        }
    }

    private fun currentBatteryLevel(): Int {
        // Sticky system broadcast — no BatteryManager permission needed.
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level > 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun showLowBatteryToast(level: Int) {
        val toast = Toast.makeText(
            this,
            getString(R.string.toast_battery_critical, level),
            Toast.LENGTH_LONG
        )
        toast.show()
        // No native "N seconds" toast duration — LENGTH_LONG then cancel.
        handler.postDelayed({ toast.cancel() }, AppSettings.toastDurationMillis(this))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(level: Int, threshold: Int): Notification {
        val critical = level >= 0 && level < threshold
        val title = if (critical) {
            getString(R.string.notif_title_critical)
        } else {
            getString(R.string.notif_title_monitoring)
        }
        val text = if (level >= 0) {
            getString(R.string.notif_text_level, level)
        } else {
            getString(R.string.notif_text_unknown)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(level: Int, threshold: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(level, threshold))
    }

    companion object {
        private const val CHANNEL_ID = "battery_monitor"
        private const val NOTIFICATION_ID = 1

        /** True while this service is running (used by MainActivity on save). */
        @Volatile
        var isRunning = false
    }
}