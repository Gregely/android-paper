package com.paperscreen

import kotlin.math.roundToInt

/**
 * How far into "night" a moment is, for Night warmth: 0 = day, 1 = night.
 *
 * Night runs from [start] to [end] minutes after midnight and may wrap past midnight
 * (21:00–07:00). With a [transitionMinutes] ramp, each changeover is a linear blend centred on
 * the changeover time (so 21:00 with 30 minutes blends from 20:45 to 21:15).
 */
object NightSchedule {

    const val MINUTES_PER_DAY = 24 * 60

    fun nightFactor(now: Int, start: Int, end: Int, transitionMinutes: Int): Float {
        if (start == end) return 0f
        val half = transitionMinutes / 2f
        if (half <= 0f) return if (isNight(now, start, end)) 1f else 0f
        // Distance past each changeover (negative = before it), wrapped to ±12 h.
        val sinceStart = wrap(now - start)
        val sinceEnd = wrap(now - end)
        return when {
            sinceStart >= -half && sinceStart <= half -> (sinceStart + half) / (2 * half)
            sinceEnd >= -half && sinceEnd <= half -> 1f - (sinceEnd + half) / (2 * half)
            isNight(now, start, end) -> 1f
            else -> 0f
        }
    }

    /** Warmth between [day] and [night] for a given night factor. */
    fun blend(day: Int, night: Int, factor: Float): Int = (day + (night - day) * factor).roundToInt()

    fun isNight(now: Int, start: Int, end: Int): Boolean =
        if (start < end) now in start until end else now >= start || now < end

    private fun wrap(minutes: Int): Float {
        var m = minutes % MINUTES_PER_DAY
        if (m > MINUTES_PER_DAY / 2) m -= MINUTES_PER_DAY
        if (m < -MINUTES_PER_DAY / 2) m += MINUTES_PER_DAY
        return m.toFloat()
    }
}
