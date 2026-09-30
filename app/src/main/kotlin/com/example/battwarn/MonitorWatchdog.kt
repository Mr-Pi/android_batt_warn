// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Liveness watchdog for [BatteryMonitorService].
 *
 * A foreground service can be killed by the system (doze, memory pressure, an
 * aggressive OEM battery manager) and a sticky restart is not dependable: on
 * Android 12+ a restarted foreground service may be refused
 * `startForeground()` and die again, and there is nothing else to bring it
 * back. The watchdog is that missing piece.
 *
 * It is an [AlarmManager] exact alarm: when it fires, the app holds a pending
 * exact alarm, which is an explicit exemption from the Android 12+ background
 * foreground-service-start restriction, so [WatchdogReceiver] can legally
 * (re)start the service. The check itself is a cheap boolean + static read, and
 * the alarm is coarse (see [WATCHDOG_INTERVAL_MS]) and only scheduled while the
 * user wants monitoring on, so it costs one wake-up every fifteen minutes at
 * most and never during an explicit Stop.
 *
 * Scheduled on Start/boot and re-armed by each receipt; cancelled on Stop.
 */
object MonitorWatchdog {

    /** Coarse liveness cadence — one wake-up every 15 minutes while enabled. */
    const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    private const val TAG = "BattWarn"
    private const val REQUEST_CODE = 0x8A7

    /** Arms the next check. Call when the user wants monitoring on. */
    fun schedule(context: Context) {
        if (!AppSettings.monitoringEnabled(context)) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent(context)
                )
            } else {
                // Without the exact-alarm grant the OS batches this, but it can
                // still fire in doze maintenance windows — a slower but valid
                // backstop rather than no watchdog at all.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent(context)
                )
            }
            Log.i(TAG, "watchdog armed for ${WATCHDOG_INTERVAL_MS / 1000}s")
        } catch (e: SecurityException) {
            Log.w(TAG, "watchdog could not be armed: $e")
        }
    }

    /** Disarms the check. Call when the user explicitly stops monitoring. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(context))
    }

    /**
     * One watchdog tick: if the user still wants monitoring but the service is
     * not running, restart it. Returns true when a restart was attempted, so
     * the decision is testable without an alarm.
     */
    fun ensureMonitoring(context: Context): Boolean {
        if (!shouldRestart(
                AppSettings.monitoringEnabled(context),
                BatteryMonitorService.isRunning
            )
        ) {
            if (!AppSettings.monitoringEnabled(context)) {
                Log.i(TAG, "watchdog: monitoring disabled, nothing to do")
            } else {
                Log.i(TAG, "watchdog: service is alive")
            }
            return false
        }
        Log.w(TAG, "watchdog: service is gone — restarting it")
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            true
        } catch (e: Exception) {
            // If the OS still refuses (e.g. quota), the next tick tries again.
            Log.w(TAG, "watchdog restart refused: $e")
            false
        }
    }

    /** The watchdog only acts when the user wants monitoring but it is not running. */
    fun shouldRestart(enabled: Boolean, running: Boolean): Boolean = enabled && !running

    /** Exact alarms need the SCHEDULE_EXACT_ALARM grant on API 31+. */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java)
                .setAction(WatchdogReceiver.ACTION_WATCHDOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
