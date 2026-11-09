package com.timekeeper.app

import android.content.SharedPreferences
import org.json.JSONObject

object MusicBpmStore {

    const val MIN_BPM = 60
    const val MAX_BPM = 200
    private const val KEY_MAP = "music_manual_bpm_map"

    fun get(prefs: SharedPreferences, uriString: String): Int {
        val raw = prefs.getString(KEY_MAP, null) ?: return 0
        return try {
            JSONObject(raw).optInt(uriString, 0)
        } catch (e: Exception) {
            0
        }
    }

    fun put(prefs: SharedPreferences, uriString: String, bpm: Int) {
        val map = try {
            JSONObject(prefs.getString(KEY_MAP, null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        map.put(uriString, bpm)
        prefs.edit().putString(KEY_MAP, map.toString()).apply()
    }
}