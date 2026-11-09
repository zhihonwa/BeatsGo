package com.timekeeper.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.timekeeper.app.databinding.ActivityVariableBpmBinding
import java.util.Locale
import kotlin.math.abs

class VariableBpmActivity : Activity() {

    private lateinit var binding: ActivityVariableBpmBinding
    private var baseBpm = 0
    private var deltas = VariableBpm.DEFAULT_DELTAS.copyOf()
    private var durations = VariableBpm.DEFAULT_DURATIONS.copyOf()

    private class StageViews(
        val bpmText: TextView,
        val deltaText: TextView?,
        val deltaMinus: TextView?,
        val deltaPlus: TextView?,
        val durationText: TextView,
        val durationMinus: TextView,
        val durationPlus: TextView
    )

    private val stages: List<StageViews> by lazy {
        listOf(
            StageViews(
                binding.stage1BpmText, null, null, null,
                binding.stage1DurationText,
                binding.stage1DurationMinus, binding.stage1DurationPlus
            ),
            StageViews(
                binding.stage2BpmText, binding.stage2DeltaText,
                binding.stage2DeltaMinus, binding.stage2DeltaPlus,
                binding.stage2DurationText,
                binding.stage2DurationMinus, binding.stage2DurationPlus
            ),
            StageViews(
                binding.stage3BpmText, binding.stage3DeltaText,
                binding.stage3DeltaMinus, binding.stage3DeltaPlus,
                binding.stage3DurationText,
                binding.stage3DurationMinus, binding.stage3DurationPlus
            ),
            StageViews(
                binding.stage4BpmText, binding.stage4DeltaText,
                binding.stage4DeltaMinus, binding.stage4DeltaPlus,
                binding.stage4DurationText,
                binding.stage4DurationMinus, binding.stage4DurationPlus
            ),
            StageViews(
                binding.stage5BpmText, binding.stage5DeltaText,
                binding.stage5DeltaMinus, binding.stage5DeltaPlus,
                binding.stage5DurationText,
                binding.stage5DurationMinus, binding.stage5DurationPlus
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVariableBpmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.variableBpmSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(VariableBpm.KEY_ENABLED, checked)
                .apply()
            render()
        }
        stages.forEachIndexed { index, views ->
            val stage = index + 1
            views.durationMinus.setOnClickListener {
                editDuration(stage, -VariableBpm.DURATION_STEP)
            }
            views.durationPlus.setOnClickListener {
                editDuration(stage, VariableBpm.DURATION_STEP)
            }
            views.deltaMinus?.setOnClickListener {
                editDelta(stage, -VariableBpm.DELTA_STEP)
            }
            views.deltaPlus?.setOnClickListener {
                editDelta(stage, VariableBpm.DELTA_STEP)
            }
        }
        binding.variableBpmResetButton.setOnClickListener { resetDefaults() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        baseBpm = prefs.getInt(TimeKeeperService.KEY_MUSIC_BPM, 0)
        deltas = VariableBpm.readDeltas(prefs)
        durations = VariableBpm.readDurations(prefs)
        binding.variableBpmSwitch.isChecked = VariableBpm.isEnabled(prefs)
        render()
    }

    private fun render() {
        val enabled = binding.variableBpmSwitch.isChecked
        binding.variableBpmStagesCard.alpha = if (enabled) 1f else 0.45f
        binding.variableBpmMusicHint.visibility =
            if (baseBpm <= 0) View.VISIBLE else View.GONE
        val bpms = if (baseBpm > 0) VariableBpm.stageBpms(baseBpm, deltas) else null
        stages.forEachIndexed { index, views ->
            val stage = index + 1
            views.bpmText.text = bpmTextFor(stage, bpms?.get(index), deltas[index])
            views.durationText.text = durations[index].toString()
            views.deltaText?.text = abs(deltas[index]).toString()
            val deltaEditable = baseBpm > 0
            views.deltaMinus?.isEnabled = deltaEditable
            views.deltaPlus?.isEnabled = deltaEditable
        }
        binding.variableBpmTotalText.text =
            getString(R.string.variable_bpm_total_value, VariableBpm.totalMinutes(durations))
        binding.variableBpmResetButton.isEnabled =
            !VariableBpm.isDefaultPlan(deltas, durations)
    }

    private fun bpmTextFor(stage: Int, bpm: Int?, delta: Int): String = when {
        bpm == null -> getString(R.string.value_unavailable)
        stage == 1 -> getString(R.string.variable_bpm_stage_bpm_auto, bpm)
        else -> getString(
            R.string.variable_bpm_stage_bpm,
            bpm,
            String.format(Locale.US, "%+d", delta)
        )
    }

    private fun editDelta(stage: Int, step: Int) {
        if (baseBpm <= 0) return
        val magnitude = abs(deltas[stage - 1])
        val nextMagnitude = (magnitude + step)
            .coerceIn(VariableBpm.MIN_DELTA, VariableBpm.MAX_DELTA)
        if (nextMagnitude == magnitude) return
        val candidate = deltas.copyOf()
        candidate[stage - 1] =
            if (VariableBpm.DEFAULT_DELTAS[stage - 1] >= 0) nextMagnitude else -nextMagnitude
        val bpms = VariableBpm.stageBpms(baseBpm, candidate)
        if (!VariableBpm.isWithinValidRange(bpms)) {
            Toast.makeText(
                this,
                getString(
                    R.string.toast_variable_bpm_range,
                    VariableBpm.MIN_STAGE_BPM,
                    VariableBpm.MAX_STAGE_BPM
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        deltas = candidate
        VariableBpm.saveDeltas(
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE),
            deltas
        )
        render()
    }

    private fun editDuration(stage: Int, step: Int) {
        val next = (durations[stage - 1] + step)
            .coerceIn(VariableBpm.MIN_DURATION, VariableBpm.MAX_DURATION)
        if (next == durations[stage - 1]) return
        durations[stage - 1] = next
        VariableBpm.saveDurations(
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE),
            durations
        )
        render()
    }

    private fun resetDefaults() {
        deltas = VariableBpm.DEFAULT_DELTAS.copyOf()
        durations = VariableBpm.DEFAULT_DURATIONS.copyOf()
        VariableBpm.saveDefaults(
            getSharedPreferences(TimeKeeperService.PREFS_NAME, MODE_PRIVATE)
        )
        render()
        Toast.makeText(this, R.string.toast_variable_reset, Toast.LENGTH_SHORT).show()
    }
}