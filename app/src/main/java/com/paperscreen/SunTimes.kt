package com.paperscreen

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset for a day and place, from NOAA's solar position approximation
 * (accurate to a couple of minutes, which is far finer than a time zone's reference point).
 */
object SunTimes {

    /** Minutes after local midnight, or null in polar day/night when the sun doesn't cross. */
    class Result(val sunrise: Int?, val sunset: Int?, val polarNight: Boolean)

    /**
     * [dayOfYear] is 1–366; [utcOffsetMinutes] is the local offset from UTC on that day
     * (including daylight saving).
     */
    fun compute(dayOfYear: Int, latitude: Double, longitude: Double, utcOffsetMinutes: Int): Result {
        // Fractional year (radians), at noon.
        val gamma = 2 * PI / 365 * (dayOfYear - 1)
        val eqTime = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(latitude)
        // Sun's centre 0.833° below the horizon (refraction + solar radius).
        val cosHa = cos(Math.toRadians(90.833)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
        if (cosHa > 1) return Result(null, null, polarNight = true)
        if (cosHa < -1) return Result(null, null, polarNight = false)
        val ha = Math.toDegrees(acos(cosHa))
        val noonUtc = 720 - 4 * longitude - eqTime
        val sunrise = noonUtc - 4 * ha + utcOffsetMinutes
        val sunset = noonUtc + 4 * ha + utcOffsetMinutes
        return Result(wrap(sunrise), wrap(sunset), polarNight = false)
    }

    private fun wrap(minutes: Double): Int {
        val m = minutes.roundToInt() % NightSchedule.MINUTES_PER_DAY
        return if (m < 0) m + NightSchedule.MINUTES_PER_DAY else m
    }
}
