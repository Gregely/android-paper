package com.paperscreen

import android.content.Context
import android.content.SharedPreferences

/** User-tunable settings, persisted in SharedPreferences and shared by the activity and service. */
class PaperSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var refreshIntervalMs: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)).apply()

    var warmth: Int
        get() = prefs.getInt(KEY_WARMTH, DEFAULT_WARMTH).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_WARMTH, value.coerceIn(0, 100)).apply()

    var contrast: Int
        get() = prefs.getInt(KEY_CONTRAST, DEFAULT_CONTRAST).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_CONTRAST, value.coerceIn(0, 100)).apply()

    var greyLevels: Int
        get() = prefs.getInt(KEY_LEVELS, DEFAULT_LEVELS).coerceIn(MIN_LEVELS, MAX_LEVELS)
        set(value) = prefs.edit().putInt(KEY_LEVELS, value.coerceIn(MIN_LEVELS, MAX_LEVELS)).apply()

    /** When true, the service only refreshes after something on screen has changed. */
    var refreshOnChange: Boolean
        get() = prefs.getBoolean(KEY_ON_CHANGE, true)
        set(value) = prefs.edit().putBoolean(KEY_ON_CHANGE, value).apply()

    var servicePromptShown: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_PROMPTED, value).apply()

    fun filterParams() = EinkFilter.Params(warmth = warmth, contrast = contrast, greyLevels = greyLevels)

    companion object {
        private const val PREFS_NAME = "paper_screen"

        const val KEY_INTERVAL = "refresh_interval_ms"
        const val KEY_WARMTH = "warmth"
        const val KEY_CONTRAST = "contrast"
        const val KEY_LEVELS = "grey_levels"
        const val KEY_ON_CHANGE = "refresh_on_change"
        private const val KEY_SERVICE_PROMPTED = "service_prompt_shown"

        const val MIN_INTERVAL_MS = 200
        const val MAX_INTERVAL_MS = 2000
        const val INTERVAL_STEP_MS = 50
        const val DEFAULT_INTERVAL_MS = 500

        const val DEFAULT_WARMTH = 20
        const val DEFAULT_CONTRAST = 50

        const val MIN_LEVELS = 4
        const val MAX_LEVELS = 32
        const val DEFAULT_LEVELS = 16
    }
}
