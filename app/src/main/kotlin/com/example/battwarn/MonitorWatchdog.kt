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
 * (re)start the service. `USE_EXACT_ALARM` is declared so the exact-alarm right
 * is auto-granted on Android 13+ — recovery never needs user interaction.
 * Each tick either restarts a dead service or — while it is alive — sends
 * `ACTION_REFRESH` so the service re-posts its notification with the actual
 * battery level (bounded staleness) and repairs a lost cadence. The check
 * itself is a cheap boolean + static read, and the alarm is coarse (see
 * [WATCHDOG_INTERVAL_MS]) and only scheduled while the user wants monitoring
 * on, so it costs one wake-up every fifteen minutes at most and never during
 * an explicit Stop.
 *
 * Scheduled on Start/boot and re-armed by each receipt *before* the receipt
 * acts on the service, so a tick that dies mid-action cannot end the chain; an
 * exact-arm refusal falls back to the inexact arm for the same reason. Cancelled
 * on Stop. Also re-armed by every successful service start while the user wants
 * monitoring, so a live service can never run without a pending watchdog alarm.
 */
object MonitorWatchdog {

    /** Coarse liveness cadence — one wake-up every 15 minutes while enabled. */
    const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    private const val TAG = "BattWarn"
    private const val REQUEST_CODE = 0x8A7

    /**
     * [SystemClock.elapsedRealtime] of the most recent successful arm
     * (-1 when never armed). Test hook proving a code path really re-armed the
     * watchdog, since no public API queries pending alarms from the app.
     */
    @Volatile
    internal var lastArmedAtElapsedMs = -1L

    /** Arms the next check. Call when the user wants monitoring on. */
    fun schedule(context: Context) {
        if (!AppSettings.monitoringEnabled(context)) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
        val alarm = pendingIntent(context)
        try {
            if (canScheduleExact(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    alarm
                )
            } else {
                armInexact(alarmManager, triggerAt, alarm)
            }
        } catch (e: SecurityException) {
            // The exact-alarm right can vanish between the check and the call
            // (revocation, OEM refusal). Never let the chain die silently:
            // fall back to the slower but always-permitted inexact arm.
            Log.w(TAG, "exact watchdog arm refused ($e) — arming inexact instead")
            try {
                armInexact(alarmManager, triggerAt, alarm)
            } catch (e2: Exception) {
                Log.w(TAG, "watchdog could not be armed at all: $e2")
                return
            }
        }
        lastArmedAtElapsedMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "watchdog armed for ${WATCHDOG_INTERVAL_MS / 1000}s")
    }

    /**
     * Without the exact-alarm grant the OS batches this, but it can still fire
     * in doze maintenance windows — a slower but valid backstop rather than no
     * watchdog at all.
     */
    private fun armInexact(alarmManager: AlarmManager, triggerAt: Long, alarm: PendingIntent) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarm)
    }

    /** Disarms the check. Call when the user explicitly stops monitoring. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(context))
    }

    /**
     * One watchdog tick: while the user wants monitoring, the service must be
     * running with a current notification. Alive: send `ACTION_REFRESH`, which
     * re-posts the notification with the actual sticky battery level and
     * repairs a lost cadence. Gone: restart it. Returns true when a restart
     * was attempted (the "tick acted" signal the tests observe).
     */
    fun ensureMonitoring(context: Context): Boolean {
        if (!AppSettings.monitoringEnabled(context)) {
            Log.i(TAG, "watchdog: monitoring disabled, nothing to do")
            return false
        }
        if (BatteryMonitorService.isRunning) {
            Log.i(TAG, "watchdog: service is alive — refreshing the notification")
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BatteryMonitorService::class.java)
                        .setAction(BatteryMonitorService.ACTION_REFRESH)
                )
            } catch (e: Exception) {
                // Should not happen (a live FGS exempts the app); log and let
                // the next tick try again.
                Log.w(TAG, "watchdog refresh failed: $e")
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

    /**
     * Exact alarms need the SCHEDULE_EXACT_ALARM right on API 31+; the declared
     * USE_EXACT_ALARM grants it automatically on API 33+ (no user interaction).
     */
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
