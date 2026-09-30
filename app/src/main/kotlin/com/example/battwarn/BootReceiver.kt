// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Auto-starts the battery monitor on device boot and after the app is updated.
 * (BOOT_COMPLETED / MY_PACKAGE_REPLACED are documented exemptions to
 * Android 12+ background-foreground-service-start restrictions.)
 *
 * Only restarts monitoring if the user still wants it ([AppSettings]): a
 * deliberate Stop must not be undone by a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (!AppSettings.monitoringEnabled(context)) {
                Log.i("BattWarn", "boot: monitoring was stopped by the user, not starting")
                return
            }
            Log.i("BattWarn", "boot: restarting monitoring ($action)")
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
            MonitorWatchdog.schedule(context)
        }
    }
}