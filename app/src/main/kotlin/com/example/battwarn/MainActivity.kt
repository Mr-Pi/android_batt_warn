package com.example.battwarn

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener { startMonitoring() }
        binding.stopButton.setOnClickListener { stopMonitoring() }
        binding.saveButton.setOnClickListener { saveSettings() }

        requestNotificationPermissionIfNeeded()
        loadSettingsIntoFields()
        refreshBattery()
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
        if (BatteryMonitorService.isRunning) {
            binding.statusText.text = getString(
                R.string.status_running,
                AppSettings.alertThreshold(this)
            )
        }
    }

    private fun loadSettingsIntoFields() {
        binding.alertField.setText(AppSettings.alertThreshold(this).toString())
        binding.intervalField.setText((AppSettings.pollIntervalMillis(this) / 1000L).toString())
        binding.durationField.setText((AppSettings.toastDurationMillis(this) / 1000L).toString())
    }

    private fun saveSettings() {
        val alert = binding.alertField.text.toString().toIntOrNull()
        val intervalSec = binding.intervalField.text.toString().toIntOrNull()
        val durationSec = binding.durationField.text.toString().toIntOrNull()

        var valid = true

        val alertValid = alert != null && alert in 0..100
        val intervalValid = intervalSec != null && intervalSec in 5..3600
        val durationValid = durationSec != null && durationSec in 1..30

        binding.alertField.error = if (alertValid) null else getString(R.string.error_alert)
        binding.intervalField.error = if (intervalValid) null else getString(R.string.error_interval)
        binding.durationField.error = if (durationValid) null else getString(R.string.error_duration)
        valid = alertValid && intervalValid && durationValid

        if (!valid) return

        AppSettings.prefs(this).edit()
            .putInt(AppSettings.KEY_ALERT_THRESHOLD, alert!!)
            .putInt(AppSettings.KEY_POLL_INTERVAL_SEC, intervalSec!!)
            .putInt(AppSettings.KEY_TOAST_DURATION_SEC, durationSec!!)
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
        val sticky = registerBatteryIntent()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level > 0 && scale > 0) {
            val percent = (level * 100) / scale
            binding.batteryText.text = getString(R.string.battery_format, percent)
            val colorRes = if (percent < AppSettings.alertThreshold(this)) {
                R.color.battery_red
            } else {
                R.color.battery_green
            }
            binding.batteryText.setTextColor(ContextCompat.getColor(this, colorRes))
        }
    }

    private fun startMonitoring() {
        requestNotificationPermissionIfNeeded()

        // Improve reliability of the background loop: exempt the app from Doze/app-standby.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                batteryOptimizationLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, BatteryMonitorService::class.java)
        )
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

    private fun registerBatteryIntent(): android.content.Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }
}