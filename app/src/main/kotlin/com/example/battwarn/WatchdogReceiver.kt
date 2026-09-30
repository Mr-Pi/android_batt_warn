// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the watchdog alarm and re-arms the next tick, then asks
 * [MonitorWatchdog] to restart the service if it has died. Manifest-declared so
 * it can run after a process kill.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        MonitorWatchdog.ensureMonitoring(context)
        // Re-arm only while the user still wants monitoring (schedule() checks).
        MonitorWatchdog.schedule(context)
    }

    companion object {
        const val ACTION_WATCHDOG = "com.example.battwarn.WATCHDOG"
    }
}
