// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
package com.example.battwarn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.battwarn.databinding.ActivityMainBinding
import com.example.battwarn.databinding.ItemThresholdBinding
import com.google.android.material.checkbox.MaterialCheckBox

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val rows = mutableListOf<ThresholdRow>()
    private var pendingSoundRow: ThresholdRow? = null

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
            pendingSoundRow?.let { row ->
                row.soundUri = uri?.toString() ?: ""
                row.updateSoundLabel()
            }
            pendingSoundRow = null
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
        binding.addThresholdButton.setOnClickListener { addRow() }
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
            getString(R.string.status_running, highestThreshold(AppSettings.thresholds(this))?.level ?: 0)
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
        binding.warnOnBatteryCheck.isChecked = AppSettings.warnOnlyOnBattery(this)
        renderRows()
    }

    private fun renderRows() {
        binding.thresholdsContainer.removeAllViews()
        rows.clear()
        AppSettings.thresholds(this).forEach { addRow(it) }
        if (rows.isEmpty()) addRow()
        updateRemoveButtons()
    }

    private fun addRow(threshold: AlertThreshold = AppSettings.DEFAULT_NEW_THRESHOLD) {
        val rowBinding = ItemThresholdBinding.inflate(
            layoutInflater,
            binding.thresholdsContainer,
            false
        )
        val row = ThresholdRow(rowBinding)
        row.bind(threshold)
        binding.thresholdsContainer.addView(rowBinding.root)
        rows.add(row)
        updateRemoveButtons()
    }

    private fun removeRow(row: ThresholdRow) {
        binding.thresholdsContainer.removeView(row.binding.root)
        rows.remove(row)
        updateRemoveButtons()
    }

    private fun updateRemoveButtons() {
        val canRemove = rows.size > 1
        rows.forEach { it.binding.removeThresholdButton.isEnabled = canRemove }
    }

    private fun pickSound(row: ThresholdRow) {
        pendingSoundRow = row
        val current = row.soundUri
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
        var valid = true
        val seenLevels = mutableSetOf<Int>()
        val thresholds = rows.mapNotNull { row ->
            val level = row.binding.levelField.text.toString().toIntOrNull()
            val interval = row.binding.intervalField.text.toString().toIntOrNull()
            val duration = row.binding.durationField.text.toString().toIntOrNull()
            var rowValid = true

            row.binding.levelLayout.error = when {
                level == null || level !in 0..100 -> {
                    rowValid = false
                    getString(R.string.error_level)
                }
                !seenLevels.add(level) -> {
                    rowValid = false
                    getString(R.string.error_duplicate)
                }
                else -> null
            }
            row.binding.intervalLayout.error = if (interval == null || interval <= 0) {
                rowValid = false
                getString(R.string.error_interval)
            } else {
                null
            }
            row.binding.durationLayout.error = if (duration == null || duration <= 0) {
                rowValid = false
                getString(R.string.error_duration)
            } else {
                null
            }

            if (!rowValid) {
                valid = false
                null
            } else {
                AlertThreshold(
                    level = level!!,
                    name = row.binding.nameField.text.toString().trim(),
                    intervalSec = interval!!,
                    durationSec = duration!!,
                    soundUri = row.soundUri,
                    lockScreen = row.binding.lockScreenCheck.isChecked,
                    overrideSilentDnd = row.binding.dndCheck.isChecked,
                    bgColor = row.bgColor,
                    fgColor = row.fgColor,
                )
            }
        }

        if (!valid || thresholds.isEmpty()) return

        AppSettings.saveThresholds(this, thresholds)
        AppSettings.saveWarnOnlyOnBattery(this, binding.warnOnBatteryCheck.isChecked)

        // Restart so the running service applies the new values immediately.
        if (BatteryMonitorService.isRunning) {
            stopService(Intent(this, BatteryMonitorService::class.java))
            ContextCompat.startForegroundService(
                this,
                Intent(this, BatteryMonitorService::class.java)
            )
        }
        val highest = thresholds.maxOf { it.level }
        binding.statusText.text = getString(R.string.status_saved, highest)
    }

    private fun refreshBattery() {
        renderLevel(levelFrom(stickyBattery()))
    }

    private fun renderLevel(percent: Int) {
        if (percent < 0) return
        binding.batteryText.text = getString(R.string.battery_format, percent)
        val highest = highestThreshold(AppSettings.thresholds(this))?.level ?: 100
        binding.batteryText.setTextColor(
            ContextCompat.getColor(
                this,
                if (percent < highest) R.color.battery_red else R.color.battery_green
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
            highestThreshold(AppSettings.thresholds(this))?.level ?: 0
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

    /** Builds the swatch strip for one color slot and wires selection. */
    private fun buildSwatchStrip(strip: LinearLayout, current: Int, onPick: (Int) -> Unit) {
        strip.removeAllViews()
        ALERT_PALETTE.forEach { color ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginEnd = dp(6)
                }
                background = swatchDrawable(color, color == current)
                contentDescription = getString(R.string.color_swatch_desc)
                setOnClickListener { onPick(color) }
            }
            strip.addView(swatch)
        }
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                val strokeColor = if (isLightColor(color)) Color.BLACK else Color.WHITE
                setStroke(dp(3), strokeColor)
            }
        }

    private fun isLightColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return luminance > 0.6
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** One editable threshold card: holds the in-memory edits until Save. */
    private inner class ThresholdRow(val binding: ItemThresholdBinding) {
        var soundUri: String = ""
        var bgColor: Int = DEFAULT_BG_COLOR
        var fgColor: Int = DEFAULT_FG_COLOR

        private val previewWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
            }
        }

        init {
            binding.removeThresholdButton.setOnClickListener { removeRow(this) }
            binding.pickSoundButton.setOnClickListener { pickSound(this) }
            binding.removeSoundButton.setOnClickListener {
                soundUri = ""
                updateSoundLabel()
            }
            // Keep the sample banner in sync with the row's name and level.
            binding.nameField.addTextChangedListener(previewWatcher)
            binding.levelField.addTextChangedListener(previewWatcher)
            refreshSwatchStrips()
        }

        fun bind(threshold: AlertThreshold) {
            binding.levelField.setText(threshold.level.toString())
            binding.nameField.setText(threshold.name)
            binding.intervalField.setText(threshold.intervalSec.toString())
            binding.durationField.setText(threshold.durationSec.toString())
            soundUri = threshold.soundUri
            binding.lockScreenCheck.isChecked = threshold.lockScreen
            binding.dndCheck.isChecked = threshold.overrideSilentDnd
            bgColor = threshold.bgColor
            fgColor = threshold.fgColor
            updateSoundLabel()
            refreshSwatchStrips()
            // The swatch strips refresh the selection rings; the sample banner
            // must also pick up the loaded colors (setText above already
            // fired updatePreview with the defaults).
            updatePreview()
        }

        fun updateSoundLabel() {
            val silent = soundUri.isEmpty()
            binding.soundValue.text = if (silent) {
                getString(R.string.sound_silent)
            } else {
                try {
                    RingtoneManager.getRingtone(this@MainActivity, Uri.parse(soundUri))
                        ?.getTitle(this@MainActivity)
                        ?: getString(R.string.sound_silent)
                } catch (e: Exception) {
                    getString(R.string.sound_silent)
                }
            }
            binding.removeSoundButton.isEnabled = !silent
        }

        private fun refreshSwatchStrips() {
            buildSwatchStrip(binding.bgSwatchStrip, bgColor) { color ->
                bgColor = color
                refreshSwatchStrips()
                updatePreview()
            }
            buildSwatchStrip(binding.fgSwatchStrip, fgColor) { color ->
                fgColor = color
                refreshSwatchStrips()
                updatePreview()
            }
        }

        private fun updatePreview() {
            // Sample of the alert message: "<name>: <level>%", using the same
            // format as the banner that triggers when the level drops below
            // this threshold.
            val name = binding.nameField.text.toString().trim()
                .ifBlank { getString(R.string.alert_name_default) }
            val level = binding.levelField.text.toString().toIntOrNull() ?: 0
            binding.previewText.backgroundTintList = ColorStateList.valueOf(bgColor)
            binding.previewText.setTextColor(fgColor)
            binding.previewText.text = getString(R.string.alert_message_format, name, level)
        }
    }
}