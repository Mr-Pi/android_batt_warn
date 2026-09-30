// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Battery-respecting foreground service.
 *
 * IDLE mode (battery at or above every threshold): no polling at all. We listen
 * for the system's ACTION_BATTERY_CHANGED broadcast (fired on every battery
 * change) and check the level on start; the service becomes ACTIVE the moment
 * the level drops below the highest configured threshold.
 *
 * ACTIVE mode picks the active tier with [activeThreshold] — the threshold with
 * the smallest level the current level is strictly below. That tier's timings,
 * sound, DND override, lock-screen flag, warn-only-on-battery flag, and colors
 * drive the alert. Each banner stays up for the tier's duration; the NEXT banner
 * is shown interval seconds AFTER the banner closes — measured from the
 * dismissal (auto-dismiss or tap), so the interval is a quiet pause between
 * alerts. Without overlay permission there is no banner and thus no dismissal
 * event; the next cycle is then scheduled interval after the previous one
 * started. While the device is locked and the display is already on, the alert
 * is shown full-screen over the lock screen by [LockScreenAlertActivity]
 * instead of the overlay (see [shouldShowOnLockScreen]); the screen is never
 * woken.
 * The FGS notification is refreshed on every battery change (in both IDLE and
 * ACTIVE) so it always reflects the current level and the active tier's name.
 * Polling stops when the battery rises back above the highest threshold or on
 * ACTION_BATTERY_OKAY.
 *
 * All thresholds/timings are read from SharedPreferences (see [AppSettings]).
 */
class BatteryMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** ACTIVE mode: the level is below at least one threshold. */
    private var polling = false

    /**
     * A [pollTask] is queued. Kept separate from [polling] so a lost dismissal
     * (or any other missed wake-up) cannot dead-end the cycle: as long as we are
     * ACTIVE, [startPolling] re-arms a next poll whenever none is pending.
     */
    private var pollScheduled = false
    private var overlayAlert: OverlayAlert? = null
    private var alertRingtone: Ringtone? = null

    /** Receivers are registered once per instance; onStartCommand can run again. */
    private var receiversRegistered = false

    private val batteryActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> startPolling()
                Intent.ACTION_BATTERY_OKAY -> stopPolling()
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> onPowerChanged()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    /** The lock-screen alert closed (auto-dismiss or tap) — resume the cycle. */
    private val lockAlertDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LockScreenAlertActivity.ACTION_LOCK_ALERT_DISMISSED) {
                onLockAlertDismissed()
            }
        }
    }

    /**
     * The doze backstop fired. The handler is suspended while the CPU sleeps, so
     * this alarm (registered dynamically, only while the service runs) is what
     * keeps the cycle advancing in deep doze. It is inexact and the OS throttles
     * it, which is the intended battery floor.
     */
    private val dozeBackstopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DOZE_BACKSTOP) {
                Log.i(TAG, "doze backstop fired")
                pollNow()
            }
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            pollScheduled = false
            cancelDozeBackstop()
            val level = currentBatteryLevel()
            if (level !in 0..100) {
                // A malformed/missing sticky broadcast must not be read as
                // "below every threshold" — that used to fire false alerts.
                Log.w(TAG, "battery level unknown; rechecking later")
                updateNotification(level, null, false)
                if (polling) scheduleNextPoll(UNKNOWN_LEVEL_RECHECK_MS) else stopPolling()
                return
            }
            val threshold = activeThreshold(level, AppSettings.thresholds(this@BatteryMonitorService))
            val suppressed = isChargingNow() && threshold?.warnOnlyOnBattery == true
            updateNotification(level, threshold, suppressed)
            when {
                suppressed -> {
                    Log.i(TAG, "ACTIVE suppressed (charging + warn-only) at $level%")
                    stopPolling()
                }
                threshold != null -> showLowBatteryAlert(level, threshold)
                else -> {
                    Log.i(TAG, "back above every threshold at $level% — IDLE")
                    stopPolling()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        createNotificationChannel()
        val thresholds = AppSettings.thresholds(this)
        val level = currentBatteryLevel()
        val threshold = activeThresholdFor(level, thresholds)
        val suppressed = threshold != null && isChargingNow() && threshold.warnOnlyOnBattery

        // A restarted service on Android 12+ can be refused the foreground
        // promotion; catch it, log it, and let the watchdog try again rather
        // than crashing the process.
        try {
            startForeground(NOTIFICATION_ID, buildNotification(level, threshold, suppressed))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refused ($e) — deferring to the watchdog")
            MonitorWatchdog.schedule(this)
            stopSelf()
            return START_NOT_STICKY
        }

        registerReceivers()
        val highest = highestThreshold(thresholds)
        when {
            suppressed -> {
                Log.i(TAG, "started while charging + warn-only; watchdog will recheck")
                scheduleNextPoll(SUPPRESSED_RECHECK_MS)
            }
            highest != null && level in 0 until highest.level -> startPolling()
            else -> Log.i(TAG, "started IDLE at $level%")
        }
        isRunning = true
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app from Recents can stop the task; keep monitoring alive.
        Log.i(TAG, "onTaskRemoved — re-arming the watchdog")
        MonitorWatchdog.ensureMonitoring(this)
        MonitorWatchdog.schedule(this)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        stopPolling()
        cancelDozeBackstop()
        overlayAlert?.hide() // may fire the dismissal callback — cancel its scheduling below
        // Preserve the user's intent across a death that we did not request, so
        // the watchdog can bring monitoring back.
        if (AppSettings.monitoringEnabled(this)) MonitorWatchdog.schedule(this)
        handler.removeCallbacksAndMessages(null)
        try {
            alertRingtone?.stop()
        } catch (e: Exception) {
            // Already stopped or invalid.
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            unregisterReceiver(batteryActionReceiver)
            unregisterReceiver(lockAlertDismissReceiver)
            unregisterReceiver(dozeBackstopReceiver)
        } else {
            @Suppress("DEPRECATION")
            unregisterReceiver(batteryActionReceiver)
            @Suppress("DEPRECATION")
            unregisterReceiver(lockAlertDismissReceiver)
            @Suppress("DEPRECATION")
            unregisterReceiver(dozeBackstopReceiver)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** ACTIVE entry / re-entry: guarantee a pending poll whenever none is queued. */
    private fun startPolling() {
        polling = true
        if (pollScheduled) return
        scheduleNextPoll(0L)
    }

    private fun stopPolling() {
        polling = false
        cancelNextPoll()
    }

    /** Queues the next poll at [delayMs] and arms the doze backstop behind it. */
    private fun scheduleNextPoll(delayMs: Long) {
        handler.removeCallbacks(pollTask)
        handler.postDelayed(pollTask, delayMs)
        pollScheduled = true
        scheduleDozeBackstop(delayMs)
    }

    private fun cancelNextPoll() {
        handler.removeCallbacks(pollTask)
        pollScheduled = false
        cancelDozeBackstop()
    }

    /** Polls immediately (used by the doze backstop). */
    private fun pollNow() {
        polling = true
        scheduleNextPoll(0L)
    }

    /** One inexact wake-up per pending cycle; the OS throttles it in deep doze. */
    private fun scheduleDozeBackstop(delayMs: Long) {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs + DOZE_BACKSTOP_SLACK_MS
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                dozeBackstopPendingIntent()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "doze backstop refused: $e")
        }
    }

    private fun cancelDozeBackstop() {
        getSystemService(AlarmManager::class.java)?.cancel(dozeBackstopPendingIntent())
    }

    private fun dozeBackstopPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            DOZE_BACKSTOP_REQUEST_CODE,
            Intent(ACTION_DOZE_BACKSTOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryActionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryActionReceiver, filter)
        }
        ContextCompat.registerReceiver(
            this,
            dozeBackstopReceiver,
            IntentFilter(ACTION_DOZE_BACKSTOP),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registerLockAlertDismissReceiver()
    }

    /** Registers for the lock-screen alert's dismissal broadcast (in-app only). */
    private fun registerLockAlertDismissReceiver() {
        ContextCompat.registerReceiver(
            this,
            lockAlertDismissReceiver,
            IntentFilter(LockScreenAlertActivity.ACTION_LOCK_ALERT_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Reacts to every battery change: refresh the notification and cross into ACTIVE if low. */
    private fun onBatteryChanged(intent: Intent?) {
        val level = levelFrom(intent)
        val threshold = activeThresholdFor(level, AppSettings.thresholds(this))
        val suppressed = threshold != null && isChargingNow() && threshold.warnOnlyOnBattery
        updateNotification(level, threshold, suppressed)
        when {
            // Charging with the active tier's warn-only flag on: pause — also
            // cancels a pending post-dismiss poll from an already-running cycle.
            suppressed -> stopPolling()
            threshold != null -> startPolling()
            else -> stopPolling()
        }
    }

    /** Plug state changed. Unplugging below a threshold resumes the alert at once. */
    private fun onPowerChanged() {
        Log.i(TAG, "power changed (charging=${isChargingNow()})")
        val level = currentBatteryLevel()
        val threshold = activeThresholdFor(level, AppSettings.thresholds(this))
        val suppressed = threshold != null && isChargingNow() && threshold.warnOnlyOnBattery
        updateNotification(level, threshold, suppressed)
        when {
            suppressed -> stopPolling()
            threshold != null -> pollNow()
            else -> stopPolling()
        }
    }

    /** The screen just came on: the user is looking, so poll now rather than wait. */
    private fun onScreenOn() {
        val level = currentBatteryLevel()
        val threshold = activeThresholdFor(level, AppSettings.thresholds(this))
        val suppressed = threshold != null && isChargingNow() && threshold.warnOnlyOnBattery
        updateNotification(level, threshold, suppressed)
        if (threshold != null && !suppressed) {
            Log.i(TAG, "screen on below threshold at $level% — polling now")
            pollNow()
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

    private fun showLowBatteryAlert(level: Int, threshold: AlertThreshold) {
        val interval = threshold.intervalSec * 1000L
        val duration = threshold.durationSec * 1000L
        val message = alertMessage(this, threshold, level)

        val overlayGranted = Settings.canDrawOverlays(this)
        val posted = when {
            // Locked with the display already on: the overlay cannot show above
            // the keyguard, so use the full-screen activity. It needs
            // SYSTEM_ALERT_WINDOW too — that permission is what lets a
            // foreground service start an activity from the background
            // (BAL_ALLOW_SAW_PERMISSION). Never wakes the screen.
            shouldShowOnLockScreen(
                isDeviceLocked(),
                isScreenOn(),
                threshold.lockScreen,
                overlayGranted
            ) -> showLockScreenAlert(message, duration, interval, threshold.bgColor, threshold.fgColor)
            overlayGranted -> showOverlayAlert(message, duration, interval, threshold.bgColor, threshold.fgColor)
            else -> false
        }

        if (posted) {
            bannerShows++
            Log.i(TAG, "banner shown (${threshold.displayName(this)} @ $level%)")
        } else {
            // No dismissal will arrive, so measure the pause from show time.
            scheduleNextPoll(interval)
        }

        if (threshold.soundUri.isNotEmpty()) {
            playAlertSound(threshold.soundUri, threshold.overrideSilentDnd)
        }
    }

    /**
     * Floating banner. Interval counts from the banner closing (auto or tap);
     * returns false when the window could not be added, so the caller falls
     * back to scheduling from show time.
     */
    private fun showOverlayAlert(
        message: String,
        duration: Long,
        interval: Long,
        bgColor: Int,
        fgColor: Int
    ): Boolean {
        val overlay = overlayAlert ?: OverlayAlert(this).also { overlayAlert = it }
        return overlay.show(message, duration, bgColor, fgColor) {
            lastBannerDismissedAtMs = SystemClock.elapsedRealtime()
            scheduleNextPoll(interval)
        }
    }

    /**
     * Full-screen alert above the lock screen, launched directly. This works
     * only because the caller checked [Settings.canDrawOverlays]: the
     * SYSTEM_ALERT_WINDOW appop is what lets the platform allow the background
     * activity start from the foreground service
     * (`BAL_ALLOW_SAW_PERMISSION`). Without it the start is silently blocked —
     * hence the gate, which falls back to the overlay/notification.
     * The dismissal broadcast drives the normal cadence; the extra delayed
     * poll is a safety net for the case where no dismissal ever arrives — it
     * keeps the cycle alive from show time (the same point the overlay's
     * auto-dismiss+interval would land on).
     */
    private fun showLockScreenAlert(
        message: String,
        duration: Long,
        interval: Long,
        bgColor: Int,
        fgColor: Int
    ): Boolean {
        return try {
            startActivity(
                Intent(this, LockScreenAlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(LockScreenAlertActivity.EXTRA_MESSAGE, message)
                    .putExtra(LockScreenAlertActivity.EXTRA_DURATION_MS, duration)
                    .putExtra(LockScreenAlertActivity.EXTRA_BG_COLOR, bgColor)
                    .putExtra(LockScreenAlertActivity.EXTRA_FG_COLOR, fgColor)
            )
            handler.removeCallbacks(pollTask)
            handler.postDelayed(pollTask, duration + interval)
            pollScheduled = true
            scheduleDozeBackstop(duration + interval)
            true
        } catch (e: Exception) {
            // Background activity start refused — the notification is the fallback.
            false
        }
    }

    /** Lock-screen alert closed (auto-dismiss or tap): pause, then next cycle. */
    private fun onLockAlertDismissed() {
        lastBannerDismissedAtMs = SystemClock.elapsedRealtime()
        val level = currentBatteryLevel()
        val threshold = activeThreshold(level, AppSettings.thresholds(this))
        val interval = (threshold?.intervalSec ?: DEFAULT_INTERVAL_SEC) * 1000L
        scheduleNextPoll(interval)
    }

    /** True while the keyguard is showing (swipe or secure lock). */
    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** True while the display is on — the lock-screen alert never wakes it. */
    private fun isScreenOn(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    /**
     * Plays the threshold's picked notification sound. By default it honors
     * the ringer: silent pick, silent/vibrate ringer, or Do Not Disturb means
     * no sound. With the threshold's [AlertThreshold.overrideSilentDnd] the
     * tone is routed to the alarm stream so it plays in silent/vibrate and
     * typical DND — except a DND rule configured to silence alarms as well,
     * which no app can pierce.
     */
    private fun playAlertSound(uriText: String, override: Boolean) {
        if (uriText.isEmpty()) return
        val ringer = getSystemService(AudioManager::class.java).ringerMode
        if (!shouldPlayAlertSound(override, ringer)) return
        try {
            val ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriText))
            alertRingtone?.stop()
            if (override) ringtone?.audioAttributes = alertSoundAlarmAttributes()
            ringtone?.play()
            alertRingtone = ringtone
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
        threshold: AlertThreshold?,
        suppressed: Boolean = false
    ): Notification {
        val title = when {
            suppressed && threshold != null -> getString(R.string.notif_title_monitoring)
            threshold != null -> threshold.displayName(this)
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
        threshold: AlertThreshold?,
        suppressed: Boolean = false
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(level, threshold, suppressed))
    }

    companion object {
        const val CHANNEL_ID = "battery_monitor"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "BattWarn"

        /** Extra slack so the backstop lands after the handler's own wake-up. */
        private const val DOZE_BACKSTOP_SLACK_MS = 10_000L
        private const val DOZE_BACKSTOP_REQUEST_CODE = 0x8A8

        /** Internal broadcast action for the doze backstop alarm. */
        const val ACTION_DOZE_BACKSTOP = "com.example.battwarn.DOZE_BACKSTOP"

        /** Re-check cadence while an unknown level or charging suppression applies. */
        private const val UNKNOWN_LEVEL_RECHECK_MS = 60_000L
        private const val SUPPRESSED_RECHECK_MS = 15 * 60_000L

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

/** Whether the alert sound is allowed at the given ringer mode. */
internal fun shouldPlayAlertSound(overrideSilentOrDnd: Boolean, ringerMode: Int): Boolean =
    overrideSilentOrDnd || ringerMode == AudioManager.RINGER_MODE_NORMAL

/**
 * Whether this alert cycle should use the full-screen lock-screen surface:
 * the setting must be on, the keyguard must be showing, the display must
 * already be on (the alert never wakes the screen), and SYSTEM_ALERT_WINDOW
 * must be granted — without it the platform silently blocks the background
 * activity start from the foreground service.
 */
internal fun shouldShowOnLockScreen(
    locked: Boolean,
    interactive: Boolean,
    setting: Boolean,
    overlayGranted: Boolean
): Boolean = setting && locked && interactive && overlayGranted

/**
 * Audio attributes that route the alert sound to the alarm stream, which
 * Android keeps audible in silent/vibrate ringer and typical Do Not Disturb.
 */
internal fun alertSoundAlarmAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()