package com.paperscreen

import android.graphics.Typeface

/** Clock sizes for the home page, in sp. */
enum class ClockSize(val sp: Float, val label: Int) {
    SMALL(56f, R.string.clock_size_small),
    MEDIUM(76f, R.string.clock_size_medium),
    LARGE(96f, R.string.clock_size_large),
    ;

    companion object {
        val DEFAULT = MEDIUM
    }
}

/** The launcher's typeface, used for everything on the home page and in the drawer. */
enum class HomeFont(val label: Int) {
    SERIF(R.string.font_serif),
    SANS(R.string.font_sans),
    MONO(R.string.font_mono),
    ;

    fun typeface(style: Int = Typeface.NORMAL): Typeface = Typeface.create(
        when (this) {
            SERIF -> Typeface.SERIF
            SANS -> Typeface.SANS_SERIF
            MONO -> Typeface.MONOSPACE
        },
        style,
    )

    companion object {
        val DEFAULT = SERIF
    }
}

/** Where Night warmth gets its idea of night from. */
enum class NightScheduleMode(val label: Int) {
    SUN(R.string.night_schedule_sun),
    CUSTOM(R.string.night_schedule_custom),
    ;

    companion object {
        val DEFAULT = SUN
    }
}
