package com.timekeeper.app

import android.content.SharedPreferences

object VariableBpm {

    const val STAGE_COUNT = 7
    const val MIN_DURATION = 0
    const val MAX_DURATION = 30
    const val DURATION_STEP = 5
    const val MIN_DELTA = -25
    const val MAX_DELTA = 25
    const val DELTA_STEP = 5

    const val KEY_ENABLED = "variable_bpm_enabled"
    private const val KEY_STAGE_PREFIX = "variable_bpm_stage"
    private const val KEY_DURATION_SUFFIX = "_duration"
    private const val KEY_DELTA_SUFFIX = "_delta"

    val DEFAULT_DELTAS = intArrayOf(0, 10, -10, 10, -10, 10, -15)
    val DEFAULT_DURATIONS = intArrayOf(10, 5, 5, 5, 5, 5, 5)

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_ENABLED, false)

    fun readDurations(prefs: SharedPreferences): IntArray =
        IntArray(STAGE_COUNT) { i ->
            prefs.getInt(stageDurationKey(i + 1), DEFAULT_DURATIONS[i])
                .coerceIn(MIN_DURATION, MAX_DURATION)
        }

    fun readDeltas(prefs: SharedPreferences): IntArray =
        IntArray(STAGE_COUNT) { i ->
            prefs.getInt(stageDeltaKey(i + 1), DEFAULT_DELTAS[i])
                .coerceIn(MIN_DELTA, MAX_DELTA)
        }

    fun saveDeltas(prefs: SharedPreferences, deltas: IntArray) {
        val editor = prefs.edit()
        for (i in 0 until STAGE_COUNT) {
            editor.putInt(stageDeltaKey(i + 1), deltas[i])
        }
        editor.apply()
    }

    fun saveDurations(prefs: SharedPreferences, durations: IntArray) {
        val editor = prefs.edit()
        for (i in 0 until STAGE_COUNT) {
            editor.putInt(stageDurationKey(i + 1), durations[i])
        }
        editor.apply()
    }

    fun saveDefaults(prefs: SharedPreferences) {
        saveDeltas(prefs, DEFAULT_DELTAS)
        saveDurations(prefs, DEFAULT_DURATIONS)
    }

    fun isDefaultPlan(deltas: IntArray, durations: IntArray): Boolean =
        deltas.contentEquals(DEFAULT_DELTAS) && durations.contentEquals(DEFAULT_DURATIONS)

    fun stageBpms(baseBpm: Int, deltas: IntArray): IntArray {
        val result = IntArray(STAGE_COUNT)
        var bpm = baseBpm
        for (i in 0 until STAGE_COUNT) {
            bpm += deltas[i]
            result[i] = bpm
        }
        return result
    }


    fun totalMinutes(durations: IntArray): Int = durations.sum()

    fun stageAt(elapsedSeconds: Int, durations: IntArray): Int {
        var acc = 0
        for (i in 0 until STAGE_COUNT) {
            acc += durations[i] * 60
            if (elapsedSeconds < acc) return i + 1
        }
        return 0
    }

    fun stageDurationKey(stage: Int): String =
        "$KEY_STAGE_PREFIX$stage$KEY_DURATION_SUFFIX"

    fun stageDeltaKey(stage: Int): String =
        "$KEY_STAGE_PREFIX$stage$KEY_DELTA_SUFFIX"
}