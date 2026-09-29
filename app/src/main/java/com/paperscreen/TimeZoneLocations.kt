package com.paperscreen

import android.content.Context
import android.icu.util.TimeZone

/**
 * A reference latitude/longitude for the device's time zone (its main city, from tzdata's
 * zone1970.tab, bundled as an asset). Good enough to estimate sunrise and sunset without
 * asking for location access.
 */
object TimeZoneLocations {

    class Location(val zone: String, val latitude: Double, val longitude: Double, val estimated: Boolean)

    @Volatile private var table: Map<String, Pair<Double, Double>>? = null

    fun forZone(context: Context, zoneId: String): Location {
        val known = load(context)
        val canonical = runCatching { TimeZone.getCanonicalID(zoneId) }.getOrNull() ?: zoneId
        (known[zoneId] ?: known[canonical])?.let { (lat, lon) -> return Location(zoneId, lat, lon, false) }
        // Unknown zone (e.g. "Etc/GMT+3"): longitude from the UTC offset, a mid latitude.
        val offsetHours = java.util.TimeZone.getTimeZone(zoneId).rawOffset / 3_600_000.0
        return Location(zoneId, FALLBACK_LATITUDE, offsetHours * 15, true)
    }

    private fun load(context: Context): Map<String, Pair<Double, Double>> {
        table?.let { return it }
        val parsed = context.assets.open(ASSET).bufferedReader().useLines(::parse)
        table = parsed
        return parsed
    }

    /** Parses lines of "Zone/Name latitude longitude"; '#' starts a comment line. */
    fun parse(lines: Sequence<String>): Map<String, Pair<Double, Double>> {
        val out = HashMap<String, Pair<Double, Double>>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(' ')
            if (parts.size != 3) continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val lon = parts[2].toDoubleOrNull() ?: continue
            out[parts[0]] = lat to lon
        }
        return out
    }

    private const val ASSET = "tz_coordinates.txt"
    private const val FALLBACK_LATITUDE = 45.0
}
