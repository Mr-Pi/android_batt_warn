package com.example.battwarn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Battery-respecting foreground service.
 *
 * IDLE mode (battery >= alert threshold): no polling at all. We listen for the
 * system's ACTION_BATTERY_CHANGED broadcast (fired on every battery change)
 * and check the level on start; the service becomes ACTIVE the moment the
 * level drops below the alert threshold.
 *
 * ACTIVE mode (battery below the alert threshold) has two tiers:
 *  - warning (critical <= level < alert): banner per warning timings.
 *  - critical (level < critical threshold): banner per critical timings and
 *    plays the configured alert sound.
 * Each banner stays up for the tier's duration; the NEXT banner is shown
 * interval seconds AFTER the banner closes — measured from the dismissal
 * (auto-dismiss or tap), so the interval is a quiet pause between alerts.
 * Without overlay permission there is no banner and thus no dismissal event;
 * the next cycle is then scheduled interval after the previous one started.
 * The FGS notification is refreshed on every battery change (in both IDLE and
 * ACTIVE) so it always reflects the current level. Polling stops when the
 * battery rises back above the alert threshold or on ACTION_BATTERY_OKAY.
 *
 * All thresholds/timings are read from SharedPreferences (see [AppSettings]).
 */
class BatteryMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var overlayAlert: OverlayAlert? = null
    private var criticalRingtone: Ringtone? = null

    private val batteryActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> startPolling()
                Intent.ACTION_BATTERY_OKAY -> stopPolling()
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            val level = currentBatteryLevel()
            val alertThreshold = AppSettings.alertThreshold(this@BatteryMonitorService)
            val criticalThreshold = AppSettings.criticalThreshold(this@BatteryMonitorService)
            updateNotification(level, alertThreshold, criticalThreshold)
            if (level >= 0 && level < alertThreshold) {
                showLowBatteryAlert(level, criticalThreshold)
            } else {
                stopPolling()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val threshold = AppSettings.alertThreshold(this)
        val criticalThreshold = AppSettings.criticalThreshold(this)
        val level = currentBatteryLevel()
        val suppressed = isChargingNow() && AppSettings.warnOnlyOnBattery(this)
        startForeground(NOTIFICATION_ID, buildNotification(level, threshold, criticalThreshold, suppressed))
        registerBatteryActions()
        if (level in 0 until threshold && !suppressed) {
            startPolling()
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopPolling()
        overlayAlert?.hide() // may fire the dismissal callback — cancel its scheduling below
        handler.removeCallbacksAndMessages(null)
        try {
            criticalRingtone?.stop()
        } catch (e: Exception) {
            // Already stopped or invalid.
        }
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
            addAction(Intent.ACTION_BATTERY_CHANGED)
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

    /** Reacts to every battery change: refresh the notification and cross into ACTIVE if low. */
    private fun onBatteryChanged(intent: Intent?) {
        val level = levelFrom(intent)
        val alertThreshold = AppSettings.alertThreshold(this)
        val criticalThreshold = AppSettings.criticalThreshold(this)
        val suppressed = isChargingNow() && AppSettings.warnOnlyOnBattery(this)
        updateNotification(level, alertThreshold, criticalThreshold, suppressed)
        when {
            // Charging with the setting on: pause — also cancels a pending
            // post-dismiss poll from an already-running cycle.
            suppressed -> stopPolling()
            level >= 0 && level < alertThreshold -> startPolling()
            else -> stopPolling()
        }
    }

    /** Plugged into AC/USB/wireless right now? */
    private fun isChargingNow(): Boolean =
        stickyBattery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    private fun currentBatteryLevel(): Int = levelFrom(stickyBattery())

    /** The sticky current battery broadcast — no permission needed. */
    private fun stickyBattery(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun levelFrom(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun showLowBatteryAlert(level: Int, criticalThreshold: Int) {
        val critical = level < criticalThreshold
        val interval = if (critical) {
            AppSettings.criticalIntervalMillis(this)
        } else {
            AppSettings.pollIntervalMillis(this)
        }
        val duration = if (critical) {
            AppSettings.criticalDurationMillis(this)
        } else {
            AppSettings.toastDurationMillis(this)
        }

        val posted = if (Settings.canDrawOverlays(this)) {
            val overlay = overlayAlert ?: OverlayAlert(this).also { overlayAlert = it }
            val message = if (critical) {
                getString(R.string.toast_battery_critical, level)
            } else {
                getString(R.string.toast_battery_low, level)
            }
            // Interval counts from the banner closing (auto or tap). If it
            // could not be added the callback never fires, so fall back to
            // scheduling from show time below.
            overlay.show(message, duration) {
                lastBannerDismissedAtMs = android.os.SystemClock.elapsedRealtime()
                handler.removeCallbacks(pollTask)
                handler.postDelayed(pollTask, interval)
            }
        } else {
            false
        }

        if (posted) {
            bannerShows++
        } else {
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, interval)
        }

        if (critical) playCriticalSound()
    }

    /** Plays the user-picked notification sound; silent pick or muted ringer means no sound. */
    private fun playCriticalSound() {
        val uriText = AppSettings.criticalSoundUri(this)
        if (uriText.isEmpty()) return
        val ringer = getSystemService(AudioManager::class.java).ringerMode
        if (ringer != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriText))
            criticalRingtone?.stop()
            ringtone?.play()
            criticalRingtone = ringtone
        } catch (e: Exception) {
            // Invalid or unplayable URI — the banner and notification still inform the user.
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        level: Int,
        alertThreshold: Int,
        criticalThreshold: Int,
        suppressed: Boolean = false
    ): Notification {
        val title = when {
            suppressed && level >= 0 && level < alertThreshold -> getString(R.string.notif_title_monitoring)
            level >= 0 && level < criticalThreshold -> getString(R.string.notif_title_critical)
            level >= 0 && level < alertThreshold -> getString(R.string.notif_title_warning)
            else -> getString(R.string.notif_title_monitoring)
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

    private fun updateNotification(
        level: Int,
        alertThreshold: Int,
        criticalThreshold: Int,
        suppressed: Boolean = false
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(level, alertThreshold, criticalThreshold, suppressed))
    }

    companion object {
        private const val CHANNEL_ID = "battery_monitor"
        private const val NOTIFICATION_ID = 1

        /** True while this service is running (used by MainActivity on save). */
        @Volatile
        var isRunning = false

        /** Change-observing counters for the alert cycle (test-facing). */
        @Volatile
        var bannerShows = 0L
            private set

        @Volatile
        var lastBannerDismissedAtMs = 0L
            private set
    }
}