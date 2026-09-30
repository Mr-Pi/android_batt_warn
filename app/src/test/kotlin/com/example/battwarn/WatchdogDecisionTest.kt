// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for the liveness watchdog decision. The alarm plumbing
 * is Android-only, but the "should we restart?" rule is plain logic and guards
 * the two behaviours that matter: never resurrect a deliberate Stop, and always
 * resurrect a service that died while the user still wants monitoring.
 */
class WatchdogDecisionTest {

    @Test
    fun restartsOnlyWhenEnabledAndNotRunning() {
        assertTrue("enabled + not running must restart", MonitorWatchdog.shouldRestart(true, false))
    }

    @Test
    fun doesNotRestartWhileAlreadyRunning() {
        assertFalse("enabled + running must not restart", MonitorWatchdog.shouldRestart(true, true))
    }

    @Test
    fun doesNotResurrectAStoppedMonitor() {
        assertFalse("disabled must never restart", MonitorWatchdog.shouldRestart(false, false))
        assertFalse("disabled must never restart", MonitorWatchdog.shouldRestart(false, true))
    }
}
