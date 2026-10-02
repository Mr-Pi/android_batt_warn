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
 *
 * The re-arm happens *first*, deliberately: acting on the service can take the
 * process down (a crash in the freshly (re)started service, a kill mid-tick),
 * and a tick that fired without arming its successor ends the whole chain
 * silently — exactly how monitoring could stay dead until the next app launch.
 * Scheduling first makes the chain crash-proof; `schedule()` is idempotent and
 * only re-arms while the user still wants monitoring.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        // Re-arm before acting (schedule() checks the monitoring-intent flag).
        MonitorWatchdog.schedule(context)
        MonitorWatchdog.ensureMonitoring(context)
    }

    companion object {
        const val ACTION_WATCHDOG = "com.example.battwarn.WATCHDOG"
    }
}
