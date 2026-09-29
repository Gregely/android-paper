package com.paperscreen

import android.content.Context
import android.content.SharedPreferences

/** User-tunable settings, persisted in SharedPreferences and shared by the activity and service. */
class PaperSettings(context: Context) {

    private val appContext: Context = context.applicationContext

    val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Works out the warmth for the time of day when Night warmth is on. */
    val nightWarmthSchedule: NightWarmth by lazy { NightWarmth(appContext, this) }

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

    /**
     * The warmth actually in use: the Night warmth schedule's value while it's on, otherwise
     * the manual [warmth] slider.
     */
    fun effectiveWarmth(): Int = if (nightWarmth) nightWarmthSchedule.status().warmth else warmth

    /** Night warmth: warmth follows the time of day, overriding [warmth]. */
    var nightWarmth: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_WARMTH, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_WARMTH, value).apply()

    var nightSchedule: NightScheduleMode
        get() = enumPref(KEY_NIGHT_SCHEDULE, NightScheduleMode.DEFAULT)
        set(value) = prefs.edit().putString(KEY_NIGHT_SCHEDULE, value.name).apply()

    /** Custom night start and end, in minutes after midnight. */
    var nightStart: Int
        get() = prefs.getInt(KEY_NIGHT_START, DEFAULT_NIGHT_START).coerceIn(0, NightSchedule.MINUTES_PER_DAY - 1)
        set(value) = prefs.edit().putInt(KEY_NIGHT_START, value).apply()

    var nightEnd: Int
        get() = prefs.getInt(KEY_NIGHT_END, DEFAULT_NIGHT_END).coerceIn(0, NightSchedule.MINUTES_PER_DAY - 1)
        set(value) = prefs.edit().putInt(KEY_NIGHT_END, value).apply()

    var dayWarmth: Int
        get() = prefs.getInt(KEY_DAY_WARMTH, DEFAULT_DAY_WARMTH).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_DAY_WARMTH, value.coerceIn(0, 100)).apply()

    var nightWarmthLevel: Int
        get() = prefs.getInt(KEY_NIGHT_WARMTH_LEVEL, DEFAULT_NIGHT_WARMTH_LEVEL).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_NIGHT_WARMTH_LEVEL, value.coerceIn(0, 100)).apply()

    /** Blend over 30 minutes around each changeover, rather than switching instantly. */
    var nightTransition: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_TRANSITION, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_TRANSITION, value).apply()

    /** Last seen Night Light state (-1 unknown, 0 off, 1 on) and when it last switched. */
    var nightLightState: Int
        get() = prefs.getInt(KEY_NIGHT_LIGHT_STATE, -1)
        set(value) = prefs.edit().putInt(KEY_NIGHT_LIGHT_STATE, value).apply()

    var nightLightFlipAt: Long
        get() = prefs.getLong(KEY_NIGHT_LIGHT_FLIP_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_NIGHT_LIGHT_FLIP_AT, value).apply()

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

    /**
     * When a "pause for 5 minutes" ends, as wall-clock time (0 when not paused). Saved so a
     * pause survives the app's process being restarted.
     */
    var snoozeUntil: Long
        get() = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_SNOOZE_UNTIL, value).apply()

    var notificationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPTED, value).apply()

    var servicePromptShown: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_PROMPTED, value).apply()

    /** Home screen favourites, in order, as flattened ComponentNames. */
    var favourites: List<String>
        get() = Favourites.decode(prefs.getString(KEY_FAVOURITES, null))
        set(value) = prefs.edit().putString(KEY_FAVOURITES, Favourites.encode(value)).apply()

    /** 24-hour clock on the home page, or null to follow the system setting. */
    var clock24h: Boolean?
        get() = if (prefs.contains(KEY_CLOCK_24H)) prefs.getBoolean(KEY_CLOCK_24H, false) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CLOCK_24H) else putBoolean(KEY_CLOCK_24H, value)
        }.apply()

    var clockSize: ClockSize
        get() = enumPref(KEY_CLOCK_SIZE, ClockSize.DEFAULT)
        set(value) = prefs.edit().putString(KEY_CLOCK_SIZE, value.name).apply()

    var showDate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DATE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DATE, value).apply()

    var showSeconds: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SECONDS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SECONDS, value).apply()

    var homeFont: HomeFont
        get() = enumPref(KEY_HOME_FONT, HomeFont.DEFAULT)
        set(value) = prefs.edit().putString(KEY_HOME_FONT, value.name).apply()

    /** Whether the one-time "make PaperScreen your home screen" prompt has been shown. */
    var homePromptShown: Boolean
        get() = prefs.getBoolean(KEY_HOME_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_PROMPTED, value).apply()

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    /**
     * Puts every setting back to its default. What isn't a setting stays: whether filtering
     * is on, a pause in progress, the home screen favourites, and which prompts were shown.
     */
    fun resetToDefaults() {
        prefs.edit().apply { RESETTABLE_KEYS.forEach(::remove) }.apply()
    }

    fun filterParams() = EinkFilter.Params(
        warmth = effectiveWarmth(),
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
        const val KEY_NIGHT_WARMTH = "night_warmth"
        const val KEY_NIGHT_SCHEDULE = "night_schedule"
        const val KEY_NIGHT_START = "night_start"
        const val KEY_NIGHT_END = "night_end"
        const val KEY_DAY_WARMTH = "night_day_warmth"
        const val KEY_NIGHT_WARMTH_LEVEL = "night_night_warmth"
        const val KEY_NIGHT_TRANSITION = "night_transition"
        private const val KEY_NIGHT_LIGHT_STATE = "night_light_state"
        private const val KEY_NIGHT_LIGHT_FLIP_AT = "night_light_flip_at"
        private const val KEY_SERVICE_PROMPTED = "service_prompt_shown"
        private const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_SNOOZE_UNTIL = "snooze_until"
        private const val KEY_NOTIFICATION_PROMPTED = "notification_prompt_shown"
        private const val KEY_FAVOURITES = "home_favourites"
        private const val KEY_CLOCK_24H = "home_clock_24h"
        private const val KEY_CLOCK_SIZE = "home_clock_size"
        private const val KEY_SHOW_DATE = "home_show_date"
        private const val KEY_SHOW_SECONDS = "home_show_seconds"
        private const val KEY_HOME_FONT = "home_font"
        private const val KEY_HOME_PROMPTED = "home_prompt_shown"

        /** Everything [resetToDefaults] clears: each setting on the settings page. */
        private val RESETTABLE_KEYS = listOf(
            KEY_PRESET, KEY_CUSTOM_INTERVAL, KEY_WARMTH, KEY_CONTRAST, KEY_LEVELS, KEY_POSTERIZE,
            KEY_ON_CHANGE, KEY_GHOSTING, KEY_GHOST_OPACITY, KEY_GHOST_FADE, KEY_FULL_REFRESH,
            KEY_FULL_REFRESH_EVERY, KEY_FLASH_DURATION,
            KEY_NIGHT_WARMTH, KEY_NIGHT_SCHEDULE, KEY_NIGHT_START, KEY_NIGHT_END, KEY_DAY_WARMTH,
            KEY_NIGHT_WARMTH_LEVEL, KEY_NIGHT_TRANSITION,
            KEY_CLOCK_24H, KEY_CLOCK_SIZE, KEY_SHOW_DATE, KEY_SHOW_SECONDS, KEY_HOME_FONT,
        )

        const val MIN_CUSTOM_INTERVAL_MS = 340
        const val MAX_CUSTOM_INTERVAL_MS = 2000
        const val CUSTOM_INTERVAL_STEP_MS = 20
        const val DEFAULT_CUSTOM_INTERVAL_MS = 500

        const val DEFAULT_WARMTH = 20
        const val DEFAULT_DAY_WARMTH = 10
        const val DEFAULT_NIGHT_WARMTH_LEVEL = 60
        const val DEFAULT_NIGHT_START = 21 * 60
        const val DEFAULT_NIGHT_END = 7 * 60
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
