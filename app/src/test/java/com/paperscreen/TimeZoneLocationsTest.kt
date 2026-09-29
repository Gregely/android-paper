package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeZoneLocationsTest {

    @Test
    fun parsesZonesAndSkipsCommentsAndJunk() {
        val table = TimeZoneLocations.parse(
            sequenceOf("# header", "", "Europe/London 51.5083 -0.1253", "bad line", "Asia/Tokyo 35.6544 139.7447"),
        )
        assertEquals(2, table.size)
        assertEquals(51.5083 to -0.1253, table["Europe/London"])
    }

    @Test
    fun bundledTableCoversCommonZones() {
        val lines = java.io.File("src/main/assets/tz_coordinates.txt").readLines().asSequence()
        val table = TimeZoneLocations.parse(lines)
        for (zone in listOf("Europe/London", "America/New_York", "Asia/Kolkata", "Australia/Sydney")) {
            assertEquals("missing $zone", true, zone in table)
        }
    }
}
