package com.paperscreen

import android.content.Context
import android.content.SharedPreferences

/** User-tunable settings, persisted in SharedPreferences and shared by the activity and service. */
class PaperSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var refreshPreset: RefreshPreset
        get() = prefs.getString(KEY_PRESET, null)
            ?.let { name -> RefreshPreset.entries.firstOrNull { it.name == name } }
            ?: RefreshPreset.DEFAULT
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    /** The interval used by [RefreshPreset.CUSTOM]. */
    var customIntervalMs: Int
        get() = prefs.getInt(KEY_CUSTOM_INTERVAL, DEFAULT_CUSTOM_INTERVAL_MS)
            .coerceIn(MIN_CUSTOM_INTERVAL_MS, MAX_CUSTOM_INTERVAL_MS)
        set(value) = prefs.edit()
            .putInt(KEY_CUSTOM_INTERVAL, value.coerceIn(MIN_CUSTOM_INTERVAL_MS, MAX_CUSTOM_INTERVAL_MS))
            .apply()

    /** The minimum time between refreshes, from the preset or the custom value. */
    val refreshIntervalMs: Int
        get() = refreshPreset.ms ?: customIntervalMs

    var warmth: Int
        get() = prefs.getInt(KEY_WARMTH, DEFAULT_WARMTH).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_WARMTH, value.coerceIn(0, 100)).apply()

    var contrast: Int
        get() = prefs.getInt(KEY_CONTRAST, DEFAULT_CONTRAST).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_CONTRAST, value.coerceIn(0, 100)).apply()

    var greyLevels: Int
        get() = prefs.getInt(KEY_LEVELS, DEFAULT_LEVELS).coerceIn(MIN_LEVELS, MAX_LEVELS)
        set(value) = prefs.edit().putInt(KEY_LEVELS, value.coerceIn(MIN_LEVELS, MAX_LEVELS)).apply()

    /** Stepped grey levels; when off, the filter keeps smooth, continuous greyscale. */
    var posterize: Boolean
        get() = prefs.getBoolean(KEY_POSTERIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_POSTERIZE, value).apply()

    /** Partial refreshes leave a fading afterimage of what changed. */
    var ghosting: Boolean
        get() = prefs.getBoolean(KEY_GHOSTING, true)
        set(value) = prefs.edit().putBoolean(KEY_GHOSTING, value).apply()

    /** Starting opacity of the ghost, in percent. */
    var ghostOpacity: Int
        get() = prefs.getInt(KEY_GHOST_OPACITY, DEFAULT_GHOST_OPACITY).coerceIn(MIN_GHOST_OPACITY, MAX_GHOST_OPACITY)
        set(value) = prefs.edit().putInt(KEY_GHOST_OPACITY, value.coerceIn(MIN_GHOST_OPACITY, MAX_GHOST_OPACITY)).apply()

    var ghostFadeMs: Int
        get() = prefs.getInt(KEY_GHOST_FADE, DEFAULT_GHOST_FADE_MS).coerceIn(MIN_GHOST_FADE_MS, MAX_GHOST_FADE_MS)
        set(value) = prefs.edit().putInt(KEY_GHOST_FADE, value.coerceIn(MIN_GHOST_FADE_MS, MAX_GHOST_FADE_MS)).apply()

    /** Periodic inverted full refreshes; when off, every refresh is partial. */
    var fullRefresh: Boolean
        get() = prefs.getBoolean(KEY_FULL_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_FULL_REFRESH, value).apply()

    /** How long a full refresh shows the inverted frame. */
    var flashDurationMs: Int
        get() = prefs.getInt(KEY_FLASH_DURATION, DEFAULT_FLASH_MS).coerceIn(MIN_FLASH_MS, MAX_FLASH_MS)
        set(value) = prefs.edit().putInt(KEY_FLASH_DURATION, value.coerceIn(MIN_FLASH_MS, MAX_FLASH_MS)).apply()

    /** When true, the service only refreshes after something on screen has changed. */
    var refreshOnChange: Boolean
        get() = prefs.getBoolean(KEY_ON_CHANGE, true)
        set(value) = prefs.edit().putBoolean(KEY_ON_CHANGE, value).apply()

    /** How many partial refreshes happen between full (inverted) refreshes. */
    var fullRefreshEvery: Int
        get() = prefs.getInt(KEY_FULL_REFRESH_EVERY, DEFAULT_FULL_REFRESH_EVERY)
            .coerceIn(MIN_FULL_REFRESH_EVERY, MAX_FULL_REFRESH_EVERY)
        set(value) = prefs.edit()
            .putInt(KEY_FULL_REFRESH_EVERY, value.coerceIn(MIN_FULL_REFRESH_EVERY, MAX_FULL_REFRESH_EVERY))
            .apply()

    /** Whether filtering is on; restored when the accessibility service reconnects. */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()

    var notificationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, value).apply()

    var servicePromptShown: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_PROMPTED, value).apply()

    fun filterParams() = EinkFilter.Params(
        warmth = warmth,
        contrast = contrast,
        // 256 levels is one per luma value: no stepping at all.
        greyLevels = if (posterize) greyLevels else SMOOTH_LEVELS,
    )

    companion object {
        private const val PREFS_NAME = "paper_screen"

        const val KEY_PRESET = "refresh_preset"
        const val KEY_CUSTOM_INTERVAL = "custom_interval_ms"
        const val KEY_WARMTH = "warmth"
        const val KEY_CONTRAST = "contrast"
        const val KEY_LEVELS = "grey_levels"
        const val KEY_ON_CHANGE = "refresh_on_change"
        const val KEY_FULL_REFRESH_EVERY = "full_refresh_every"
        const val KEY_POSTERIZE = "posterize"
        const val KEY_GHOSTING = "ghosting"
        const val KEY_GHOST_OPACITY = "ghost_opacity"
        const val KEY_GHOST_FADE = "ghost_fade_ms"
        const val KEY_FULL_REFRESH = "full_refresh"
        const val KEY_FLASH_DURATION = "flash_duration_ms"
        private const val KEY_SERVICE_PROMPTED = "service_prompt_shown"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_NOTIFICATION_PROMPTED = "notification_prompt_shown"

        const val MIN_CUSTOM_INTERVAL_MS = 340
        const val MAX_CUSTOM_INTERVAL_MS = 2000
        const val CUSTOM_INTERVAL_STEP_MS = 20
        const val DEFAULT_CUSTOM_INTERVAL_MS = 500

        const val DEFAULT_WARMTH = 20
        const val DEFAULT_CONTRAST = 50

        const val MIN_LEVELS = 4
        const val MAX_LEVELS = 32
        const val DEFAULT_LEVELS = 16
        const val SMOOTH_LEVELS = 256

        const val MIN_GHOST_OPACITY = 5
        const val MAX_GHOST_OPACITY = 60
        const val DEFAULT_GHOST_OPACITY = 30
        const val MIN_GHOST_FADE_MS = 100
        const val MAX_GHOST_FADE_MS = 1000
        const val GHOST_FADE_STEP_MS = 50
        const val DEFAULT_GHOST_FADE_MS = 300

        const val MIN_FLASH_MS = 150
        const val MAX_FLASH_MS = 600
        const val FLASH_STEP_MS = 25
        const val DEFAULT_FLASH_MS = 350

        const val MIN_FULL_REFRESH_EVERY = 10
        const val MAX_FULL_REFRESH_EVERY = 100
        const val DEFAULT_FULL_REFRESH_EVERY = 50
    }
}
