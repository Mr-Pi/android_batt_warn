package com.example.battwarn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Auto-starts the battery monitor whenever the device boots.
 * Also restarts it after the app is updated/replaced.
 * (BOOT_COMPLETED / MY_PACKAGE_REPLACED are documented exemptions to
 * Android 12+ background-foreground-service-start restrictions.)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
        }
    }
}