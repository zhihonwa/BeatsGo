package com.timekeeper.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import com.timekeeper.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private val countdownUpdater = object : Runnable {
        override fun run() {
            renderCountdown()
            uiHandler.postDelayed(this, 250L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TimeKeeperService.ACTION_BPM_UPDATED -> updateMusicUi()
                else -> refreshButtonState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        val savedInterval = prefs.getInt(
            TimeKeeperService.KEY_INTERVAL,
            TimeKeeperService.DEFAULT_INTERVAL
        ).coerceIn(TimeKeeperService.MIN_INTERVAL, TimeKeeperService.MAX_INTERVAL)
        binding.intervalInput.setText(savedInterval.toString())

        binding.startButton.setOnClickListener { toggleReporting() }
        binding.pauseButton.setOnClickListener { togglePauseTimer() }
        binding.pickMusicButton.setOnClickListener { pickMusic() }
        binding.clearMusicButton.setOnClickListener { clearMusic() }
        binding.stabilityButton.setOnClickListener { showStabilityDialog() }
        binding.customizePromptText.setOnClickListener {
            startActivity(Intent(this, VoicePromptActivity::class.java))
        }
        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.musicVolumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.musicVolumeLabel.text =
                    getString(R.string.volume_percent, progress)
                if (!fromUser) return
                getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(TimeKeeperService.KEY_VOLUME, progress)
                    .apply()
                if (TimeKeeperService.isReporting) {
                    startForegroundService(
                        Intent(this@MainActivity, TimeKeeperService::class.java)
                            .setAction(TimeKeeperService.ACTION_SET_VOLUME)
                            .putExtra(TimeKeeperService.EXTRA_VOLUME_PERCENT, progress)
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.musicSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                val base = prefs.getInt(TimeKeeperService.KEY_MUSIC_BPM, 0)
                if (base <= 0) return
                val delta = progress - TimeKeeperService.MAX_BPM_DELTA
                binding.musicSpeedLabel.text = (base + delta).toString()
                if (!fromUser) return
                prefs.edit()
                    .putInt(TimeKeeperService.KEY_SPEED_DELTA, delta)
                    .apply()
                if (TimeKeeperService.isReporting) {
                    startForegroundService(
                        Intent(this@MainActivity, TimeKeeperService::class.java)
                            .setAction(TimeKeeperService.ACTION_SET_SPEED)
                            .putExtra(
                                TimeKeeperService.EXTRA_SPEED_RATE,
                                (base + delta) / base.toFloat()
                            )
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.volumeMinusButton.setOnClickListener { applyVolumeDelta(-VOLUME_STEP) }
        binding.volumePlusButton.setOnClickListener { applyVolumeDelta(VOLUME_STEP) }
        binding.bpmMinusButton.setOnClickListener { applyBpmDelta(-TimeKeeperService.BPM_STEP) }
        binding.bpmPlusButton.setOnClickListener { applyBpmDelta(TimeKeeperService.BPM_STEP) }

        updateMusicUi()
        renderCountdown()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TimeKeeperService.ACTION_STATE_CHANGED)
        filter.addAction(TimeKeeperService.ACTION_BPM_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        refreshButtonState()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
    }

    override fun onResume() {
        super.onResume()
        renderCountdown()
        uiHandler.postDelayed(countdownUpdater, 250L)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(countdownUpdater)
    }

    private fun toggleReporting() {
        if (TimeKeeperService.isReporting) {
            hapticTap()
            sendServiceAction(TimeKeeperService.ACTION_PAUSE)
        } else {
            val interval = resolveInterval() ?: return
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(TimeKeeperService.KEY_INTERVAL, interval)
                .apply()
            ensureStabilitySettings()
            val intent = Intent(this, TimeKeeperService::class.java)
                .setAction(TimeKeeperService.ACTION_RESUME)
                .putExtra(TimeKeeperService.EXTRA_INTERVAL_MINUTES, interval)
            startForegroundService(intent)
            hapticTap()
            Toast.makeText(
                this,
                getString(R.string.toast_started, interval),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun togglePauseTimer() {
        hapticTap()
        sendServiceAction(
            if (TimeKeeperService.isPaused) {
                TimeKeeperService.ACTION_RESUME_TIMER
            } else {
                TimeKeeperService.ACTION_PAUSE_TIMER
            }
        )
    }

    private fun sendServiceAction(action: String) {
        startForegroundService(
            Intent(this, TimeKeeperService::class.java).setAction(action)
        )
    }

    private fun resolveInterval(): Int? {
        val text = binding.intervalInput.text?.toString()?.trim().orEmpty()
        val value = text.toIntOrNull()
        if (value == null ||
            value < TimeKeeperService.MIN_INTERVAL ||
            value > TimeKeeperService.MAX_INTERVAL
        ) {
            binding.intervalInput.setText(TimeKeeperService.DEFAULT_INTERVAL.toString())
            Toast.makeText(
                this,
                getString(
                    R.string.toast_invalid_interval,
                    TimeKeeperService.MIN_INTERVAL,
                    TimeKeeperService.MAX_INTERVAL,
                    TimeKeeperService.DEFAULT_INTERVAL
                ),
                Toast.LENGTH_SHORT
            ).show()
            return null
        }
        return value
    }

    private fun pickMusic() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                },
                REQUEST_CODE_PICK_MUSIC
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearMusic() {
        getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(TimeKeeperService.KEY_MUSIC_URI)
            .remove(TimeKeeperService.KEY_MUSIC_NAME)
            .remove(TimeKeeperService.KEY_MUSIC_BPM)
            .remove(TimeKeeperService.KEY_SPEED_DELTA)
            .apply()
        updateMusicUi()
        if (TimeKeeperService.isReporting) {
            startForegroundService(
                Intent(this, TimeKeeperService::class.java)
                    .setAction(TimeKeeperService.ACTION_SET_MUSIC)
            )
        }
    }

    private fun updateMusicUi() {
        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        val uri = prefs.getString(TimeKeeperService.KEY_MUSIC_URI, null)
        val name = prefs.getString(TimeKeeperService.KEY_MUSIC_NAME, null)
        val hasMusic = uri != null
        if (hasMusic) {
            val displayName = name ?: getString(R.string.music_unknown_file)
            binding.pickMusicButton.text = strippedFileName(displayName)
            binding.pickMusicButton.contentDescription = displayName
            binding.clearMusicButton.visibility = View.VISIBLE
        } else {
            binding.pickMusicButton.setText(R.string.pick_music)
            binding.pickMusicButton.contentDescription = null
            binding.clearMusicButton.visibility = View.GONE
        }

        binding.musicVolumeSeek.isEnabled = hasMusic
        binding.volumeMinusButton.isEnabled = hasMusic
        binding.volumePlusButton.isEnabled = hasMusic
        val volume = prefs.getInt(
            TimeKeeperService.KEY_VOLUME,
            TimeKeeperService.DEFAULT_VOLUME_PERCENT
        ).coerceIn(0, 100)
        binding.musicVolumeSeek.progress = volume
        if (hasMusic) {
            binding.musicVolumeLabel.text = getString(R.string.volume_percent, volume)
        } else {
            binding.musicVolumeLabel.setText(R.string.value_unavailable)
        }

        val baseBpm = if (hasMusic) prefs.getInt(TimeKeeperService.KEY_MUSIC_BPM, 0) else 0
        binding.musicSpeedSeek.isEnabled = baseBpm > 0
        binding.bpmMinusButton.isEnabled = baseBpm > 0
        binding.bpmPlusButton.isEnabled = baseBpm > 0
        if (baseBpm > 0) {
            val delta = prefs.getInt(
                TimeKeeperService.KEY_SPEED_DELTA,
                TimeKeeperService.DEFAULT_BPM_DELTA
            ).coerceIn(TimeKeeperService.MIN_BPM_DELTA, TimeKeeperService.MAX_BPM_DELTA)
            binding.musicSpeedSeek.progress = delta - TimeKeeperService.MIN_BPM_DELTA
            binding.musicSpeedLabel.text = (baseBpm + delta).toString()
        } else {
            binding.musicSpeedSeek.progress = -TimeKeeperService.MIN_BPM_DELTA
            binding.musicSpeedLabel.setText(R.string.value_unavailable)
        }
    }

    private fun applyVolumeDelta(delta: Int) {
        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        val next = (
            prefs.getInt(
                TimeKeeperService.KEY_VOLUME,
                TimeKeeperService.DEFAULT_VOLUME_PERCENT
            ) + delta
            ).coerceIn(0, 100)
        binding.musicVolumeSeek.progress = next
        binding.musicVolumeLabel.text = getString(R.string.volume_percent, next)
        prefs.edit().putInt(TimeKeeperService.KEY_VOLUME, next).apply()
        if (TimeKeeperService.isReporting) {
            startForegroundService(
                Intent(this, TimeKeeperService::class.java)
                    .setAction(TimeKeeperService.ACTION_SET_VOLUME)
                    .putExtra(TimeKeeperService.EXTRA_VOLUME_PERCENT, next)
            )
        }
    }

    private fun applyBpmDelta(delta: Int) {
        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        val base = prefs.getInt(TimeKeeperService.KEY_MUSIC_BPM, 0)
        if (base <= 0) return
        val next = (
            prefs.getInt(
                TimeKeeperService.KEY_SPEED_DELTA,
                TimeKeeperService.DEFAULT_BPM_DELTA
            ) + delta
            ).coerceIn(TimeKeeperService.MIN_BPM_DELTA, TimeKeeperService.MAX_BPM_DELTA)
        binding.musicSpeedSeek.progress = next - TimeKeeperService.MIN_BPM_DELTA
        binding.musicSpeedLabel.text = (base + next).toString()
        prefs.edit().putInt(TimeKeeperService.KEY_SPEED_DELTA, next).apply()
        if (TimeKeeperService.isReporting) {
            startForegroundService(
                Intent(this, TimeKeeperService::class.java)
                    .setAction(TimeKeeperService.ACTION_SET_SPEED)
                    .putExtra(TimeKeeperService.EXTRA_SPEED_RATE, (base + next) / base.toFloat())
            )
        }
    }

    private fun startBpmDetection(uriString: String) {
        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().remove(TimeKeeperService.KEY_MUSIC_BPM).apply()
        updateMusicUi()
        Thread {
            val bpm = try {
                BpmDetector.detect(applicationContext, Uri.parse(uriString))
            } catch (e: Exception) {
                null
            }
            if (bpm != null &&
                prefs.getString(TimeKeeperService.KEY_MUSIC_URI, null) == uriString
            ) {
                prefs.edit().putInt(TimeKeeperService.KEY_MUSIC_BPM, bpm).apply()
            }
            uiHandler.post { updateMusicUi() }
        }.start()
    }

    private fun strippedFileName(name: String): String {
        val trimmed = name.trim()
        return if (trimmed.contains('.')) trimmed.substringBeforeLast('.') else trimmed
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: getString(R.string.music_unknown_file)
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) name = cursor.getString(index)
                    }
                }
        } catch (e: Exception) {
        }
        return name
    }

    private fun showStabilityDialog() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val notificationOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val alarmOk = Build.VERSION.SDK_INT < 31 ||
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        val appName = getString(R.string.app_name)
        val message = buildString {
            appendLine(if (batteryOk) "✓ 已忽略电池优化" else "✗ 未忽略电池优化")
            appendLine(if (notificationOk) "✓ 通知权限已授予" else "✗ 通知权限未授予")
            appendLine(
                if (alarmOk) "✓ 精确闹钟已允许"
                else "✗ 精确闹钟未允许（兜底降级为非精确定时）"
            )
            appendLine()
            appendLine("厂商后台管理需在系统设置中手动开启：")
            appendLine()
            appendLine("小米：设置→应用设置→$appName→省电策略→无限制；自启动→允许")
            appendLine()
            appendLine("华为：设置→应用→应用启动管理→$appName→关闭自动管理，三项全允许")
            appendLine()
            appendLine("OPPO/一加：设置→电池→应用耗电管理→允许后台运行")
            appendLine()
            appendLine("vivo：设置→电池→后台耗电管理→允许后台高耗电；自启动→允许")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.stability_title)
            .setMessage(message)
            .setPositiveButton(R.string.stability_grant) { _, _ -> ensureStabilitySettings() }
            .setNegativeButton(R.string.stability_close, null)
            .show()
    }

    private fun ensureStabilitySettings() {
        ensureNotificationPermission()
        ensureBatteryOptimizationExemption()
        ensureExactAlarmPermission()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATION
            )
        }
    }

    private fun ensureBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) return
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        if (alarmManager.canScheduleExactAlarms()) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
        }
    }

    private fun refreshButtonState() {
        val reporting = TimeKeeperService.isReporting
        binding.startButton.setText(
            if (reporting) R.string.stop_reporting else R.string.start_reporting
        )
        binding.intervalInput.isEnabled = !reporting
        binding.pauseButton.isEnabled = reporting
        binding.pauseButton.setText(
            if (TimeKeeperService.isPaused) {
                R.string.resume_reporting
            } else {
                R.string.pause_reporting
            }
        )
        renderCountdown()
    }

    private fun renderCountdown() {
        val reporting = TimeKeeperService.isReporting
        if (reporting) {
            val remaining = TimeKeeperService.computeRemainingSeconds().coerceAtLeast(0)
            binding.countdownText.text =
                String.format(Locale.US, "%02d:%02d", remaining / 60, remaining % 60)
            binding.countdownText.setTextColor(getColor(R.color.primary))
            val elapsed = TimeKeeperService.computeElapsedSeconds().coerceAtLeast(0)
            binding.elapsedText.text = String.format(
                Locale.US,
                "%02d:%02d:%02d",
                elapsed / 3600,
                elapsed % 3600 / 60,
                elapsed % 60
            )
        } else {
            binding.countdownText.setText(R.string.countdown_idle)
            binding.countdownText.setTextColor(getColor(R.color.disabled_text))
            binding.elapsedText.setText(R.string.elapsed_idle)
        }
    }

    private fun hapticTap() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val manager =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (e: Exception) {
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_PICK_MUSIC || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        val name = queryDisplayName(uri)
        getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(TimeKeeperService.KEY_MUSIC_URI, uri.toString())
            .putString(TimeKeeperService.KEY_MUSIC_NAME, name)
            .remove(TimeKeeperService.KEY_SPEED_DELTA)
            .apply()
        startBpmDetection(uri.toString())
        if (TimeKeeperService.isReporting) {
            startForegroundService(
                Intent(this, TimeKeeperService::class.java)
                    .setAction(TimeKeeperService.ACTION_SET_MUSIC)
                    .putExtra(TimeKeeperService.EXTRA_MUSIC_URI, uri.toString())
            )
        }
        Toast.makeText(
            this,
            getString(R.string.toast_music_selected, name),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private const val REQUEST_CODE_NOTIFICATION = 100
        private const val REQUEST_CODE_PICK_MUSIC = 101
        private const val VOLUME_STEP = 5
    }
}