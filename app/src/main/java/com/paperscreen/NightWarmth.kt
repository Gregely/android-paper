package com.paperscreen

import android.content.Context
import android.provider.Settings
import java.util.Calendar
import java.util.TimeZone

/**
 * Night warmth: the filter's warmth follows the time of day, blending between the day and
 * night values (over 30 minutes when the transition is on).
 *
 * Sources for "night":
 * - **Custom**: the user's start/end times, with the ramp centred on each changeover.
 * - **Sunrise/sunset**: if Android's Night Light is set to its own sunrise/sunset schedule,
 *   follow it — the system knows the real location. Only its on/off state is readable, so the
 *   ramp starts when it switches. Otherwise, sunrise/sunset are estimated for the time zone's
 *   reference city ([TimeZoneLocations]) and the ramp is centred on them.
 */
class NightWarmth(private val context: Context, private val settings: PaperSettings) {

    enum class Source { CUSTOM, NIGHT_LIGHT, SUN_ESTIMATE }

    class Status(
        val warmth: Int,
        val nightFactor: Float,
        val source: Source,
        /** Estimated times (minutes after midnight) for [Source.SUN_ESTIMATE]. */
        val sunrise: Int? = null,
        val sunset: Int? = null,
        val zone: String? = null,
    )

    fun status(nowMillis: Long = System.currentTimeMillis()): Status {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val transition = if (settings.nightTransition) TRANSITION_MINUTES else 0
        val day = settings.dayWarmth
        val night = settings.nightWarmthLevel

        if (settings.nightSchedule == NightScheduleMode.CUSTOM) {
            val factor = NightSchedule.nightFactor(minute, settings.nightStart, settings.nightEnd, transition)
            return Status(NightSchedule.blend(day, night, factor), factor, Source.CUSTOM)
        }

        nightLightActive()?.let { active ->
            val factor = nightLightFactor(active, nowMillis, transition)
            return Status(NightSchedule.blend(day, night, factor), factor, Source.NIGHT_LIGHT)
        }

        val zone = TimeZone.getDefault()
        val location = TimeZoneLocations.forZone(context, zone.id)
        val sun = SunTimes.compute(
            calendar.get(Calendar.DAY_OF_YEAR),
            location.latitude,
            location.longitude,
            zone.getOffset(nowMillis) / 60_000,
        )
        val sunrise = sun.sunrise
        val sunset = sun.sunset
        val factor = when {
            sunrise == null || sunset == null -> if (sun.polarNight) 1f else 0f
            else -> NightSchedule.nightFactor(minute, sunset, sunrise, transition)
        }
        return Status(NightSchedule.blend(day, night, factor), factor, Source.SUN_ESTIMATE, sunrise, sunset, zone.id)
    }

    /**
     * Night Light's current state, if it's set to follow sunrise/sunset; null otherwise (or if
     * the system won't say).
     */
    private fun nightLightActive(): Boolean? = try {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, NIGHT_DISPLAY_AUTO_MODE, 0) != AUTO_MODE_TWILIGHT) {
            null
        } else {
            Settings.Secure.getInt(resolver, NIGHT_DISPLAY_ACTIVATED, 0) == 1
        }
    } catch (e: RuntimeException) {
        null
    }

    /** Ramps from the moment Night Light was seen to switch; instant if that moment is unknown. */
    private fun nightLightFactor(active: Boolean, nowMillis: Long, transitionMinutes: Int): Float {
        val state = if (active) 1 else 0
        val seen = settings.nightLightState
        if (seen != state) {
            // First sighting (seen == -1) has no known switch time, so no ramp.
            settings.nightLightFlipAt = if (seen == -1) 0L else nowMillis
            settings.nightLightState = state
        }
        val flipAt = settings.nightLightFlipAt
        val target = if (active) 1f else 0f
        if (transitionMinutes == 0 || flipAt == 0L) return target
        val progress = ((nowMillis - flipAt) / (transitionMinutes * 60_000f)).coerceIn(0f, 1f)
        return if (active) progress else 1f - progress
    }

    companion object {
        const val TRANSITION_MINUTES = 30

        // Android's Night Light settings, readable (@Readable) system settings.
        private const val NIGHT_DISPLAY_AUTO_MODE = "night_display_auto_mode"
        private const val NIGHT_DISPLAY_ACTIVATED = "night_display_activated"
        private const val AUTO_MODE_TWILIGHT = 2
    }
}
