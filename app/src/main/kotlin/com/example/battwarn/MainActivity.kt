// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.battwarn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permission is only needed for the fallback notification.
        }

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Result irrelevant; the system settings screen shows the toggle.
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Result irrelevant; the system settings screen shows the toggle.
        }

    private val soundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            AppSettings.prefs(this).edit()
                .putString(AppSettings.KEY_CRITICAL_SOUND_URI, uri?.toString() ?: "")
                .apply()
            updateSoundLabel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The app draws behind the status bar; keep its icons readable by
        // using dark icons in light mode (white icons in dark mode).
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        val darkMode = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !darkMode
        insetsController.isAppearanceLightNavigationBars = !darkMode
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        binding.monitorButton.setOnClickListener { toggleMonitoring() }
        binding.saveButton.setOnClickListener { saveSettings() }
        binding.pickSoundButton.setOnClickListener { pickSound() }
        binding.removeSoundButton.setOnClickListener { removeSound() }
        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        loadSettingsIntoFields()
        refreshBattery()
    }

    override fun onStart() {
        super.onStart()
        // Sticky broadcast: registering delivers the current level at once and
        // every change afterwards keeps the big % live while we are visible.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(batteryReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshMonitorControls()
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderLevel(levelFrom(intent))
        }
    }

    private fun refreshMonitorControls() {
        val running = BatteryMonitorService.isRunning
        binding.monitorButton.setText(
            if (running) R.string.stop_monitoring else R.string.start_monitoring
        )
        binding.statusText.text = if (running) {
            getString(R.string.status_running, AppSettings.alertThreshold(this))
        } else {
            getString(R.string.status_stopped)
        }
    }

    private fun toggleMonitoring() {
        if (BatteryMonitorService.isRunning) {
            stopMonitoring()
            binding.monitorButton.setText(R.string.start_monitoring)
            binding.statusText.text = getString(R.string.status_stopped)
        } else {
            startMonitoring()
        }
    }

    private fun loadSettingsIntoFields() {
        binding.alertField.setText(AppSettings.alertThreshold(this).toString())
        binding.intervalField.setText((AppSettings.pollIntervalMillis(this) / 1000L).toString())
        binding.durationField.setText((AppSettings.toastDurationMillis(this) / 1000L).toString())
        binding.criticalField.setText(AppSettings.criticalThreshold(this).toString())
        binding.criticalIntervalField.setText((AppSettings.criticalIntervalMillis(this) / 1000L).toString())
        binding.criticalDurationField.setText((AppSettings.criticalDurationMillis(this) / 1000L).toString())
        binding.warnOnBatteryCheck.isChecked = AppSettings.warnOnlyOnBattery(this)
        updateSoundLabel()
    }

    private fun updateSoundLabel() {
        val uriText = AppSettings.criticalSoundUri(this)
        val silent = uriText.isEmpty()
        binding.soundValue.text = if (silent) {
            getString(R.string.sound_silent)
        } else {
            try {
                RingtoneManager.getRingtone(this, Uri.parse(uriText))?.getTitle(this)
                    ?: getString(R.string.sound_silent)
            } catch (e: Exception) {
                getString(R.string.sound_silent)
            }
        }
        binding.removeSoundButton.isEnabled = !silent
    }

    private fun removeSound() {
        AppSettings.prefs(this).edit()
            .putString(AppSettings.KEY_CRITICAL_SOUND_URI, "")
            .apply()
        updateSoundLabel()
    }

    private fun pickSound() {
        val current = AppSettings.criticalSoundUri(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.label_sound))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                if (current.isEmpty()) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                } else {
                    Uri.parse(current)
                }
            )
        }
        soundPickerLauncher.launch(intent)
    }

    private fun saveSettings() {
        val alert = binding.alertField.text.toString().toIntOrNull()
        val intervalSec = binding.intervalField.text.toString().toIntOrNull()
        val durationSec = binding.durationField.text.toString().toIntOrNull()
        val critical = binding.criticalField.text.toString().toIntOrNull()
        val criticalIntervalSec = binding.criticalIntervalField.text.toString().toIntOrNull()
        val criticalDurationSec = binding.criticalDurationField.text.toString().toIntOrNull()

        var valid = true

        val alertValid = alert != null && alert in 0..100
        val intervalValid = intervalSec != null && intervalSec > 0
        val durationValid = durationSec != null && durationSec > 0
        val criticalValid = critical != null && critical in 0..100
        val criticalIntervalValid = criticalIntervalSec != null && criticalIntervalSec > 0
        val criticalDurationValid = criticalDurationSec != null && criticalDurationSec > 0
        val criticalLeAlert = critical != null && alert != null && critical <= alert

        binding.alertField.error = if (alertValid) null else getString(R.string.error_alert)
        binding.intervalField.error = if (intervalValid) null else getString(R.string.error_interval)
        binding.durationField.error = if (durationValid) null else getString(R.string.error_duration)
        binding.criticalField.error = when {
            !criticalValid -> getString(R.string.error_alert)
            !criticalLeAlert -> getString(R.string.error_critical_le_alert)
            else -> null
        }
        binding.criticalIntervalField.error = if (criticalIntervalValid) {
            null
        } else {
            getString(R.string.error_interval)
        }
        binding.criticalDurationField.error = if (criticalDurationValid) {
            null
        } else {
            getString(R.string.error_duration)
        }
        valid = alertValid && intervalValid && durationValid &&
            criticalValid && criticalIntervalValid && criticalDurationValid && criticalLeAlert

        if (!valid) return

        AppSettings.prefs(this).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, alert!!)
            .putInt(AppSettings.KEY_POLL_INTERVAL_SEC, intervalSec!!)
            .putInt(AppSettings.KEY_TOAST_DURATION_SEC, durationSec!!)
            .putInt(AppSettings.KEY_CRITICAL_THRESHOLD, critical!!)
            .putInt(AppSettings.KEY_CRITICAL_INTERVAL_SEC, criticalIntervalSec!!)
            .putInt(AppSettings.KEY_CRITICAL_DURATION_SEC, criticalDurationSec!!)
            .putBoolean(AppSettings.KEY_WARN_ONLY_ON_BATTERY, binding.warnOnBatteryCheck.isChecked)
            .apply()

        // Restart so the running service applies the new values immediately.
        if (BatteryMonitorService.isRunning) {
            stopService(Intent(this, BatteryMonitorService::class.java))
            ContextCompat.startForegroundService(
                this,
                Intent(this, BatteryMonitorService::class.java)
            )
        }
        binding.statusText.text = getString(R.string.status_saved, alert)
    }

    private fun refreshBattery() {
        renderLevel(levelFrom(stickyBattery()))
    }

    private fun renderLevel(percent: Int) {
        if (percent < 0) return
        binding.batteryText.text = getString(R.string.battery_format, percent)
        binding.batteryText.setTextColor(
            ContextCompat.getColor(
                this,
                if (percent < AppSettings.alertThreshold(this)) R.color.battery_red
                else R.color.battery_green
            )
        )
    }

    private fun levelFrom(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun startMonitoring() {
        requestNotificationPermissionIfNeeded()

        // Needed so the alert banner is visible even over other apps.
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        // Improve reliability of the background loop: exempt the app from Doze/app-standby.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryOptimizationLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, BatteryMonitorService::class.java)
        )
        binding.monitorButton.setText(R.string.stop_monitoring)
        binding.statusText.text = getString(
            R.string.status_running,
            AppSettings.alertThreshold(this)
        )
    }

    private fun stopMonitoring() {
        stopService(Intent(this, BatteryMonitorService::class.java))
        binding.statusText.text = getString(R.string.status_stopped)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun stickyBattery(): android.content.Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }
}