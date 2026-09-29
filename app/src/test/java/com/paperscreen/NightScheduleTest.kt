package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class NightScheduleTest {

    private val start = 21 * 60 // 21:00
    private val end = 7 * 60 // 07:00

    private fun factor(h: Int, m: Int, transition: Int = 30) =
        NightSchedule.nightFactor(h * 60 + m, start, end, transition)

    @Test
    fun instantScheduleWrapsPastMidnight() {
        assertEquals(0f, factor(12, 0, transition = 0), 0f)
        assertEquals(1f, factor(21, 0, transition = 0), 0f)
        assertEquals(1f, factor(2, 0, transition = 0), 0f)
        assertEquals(0f, factor(7, 0, transition = 0), 0f)
    }

    @Test
    fun transitionIsCentredOnEachChangeover() {
        assertEquals(0f, factor(20, 45), 0.001f)
        assertEquals(0.5f, factor(21, 0), 0.001f)
        assertEquals(1f, factor(21, 15), 0.001f)
        assertEquals(1f, factor(6, 45), 0.001f)
        assertEquals(0.5f, factor(7, 0), 0.001f)
        assertEquals(0f, factor(7, 15), 0.001f)
        assertEquals(1f, factor(0, 0), 0f)
        assertEquals(0f, factor(15, 0), 0f)
    }

    @Test
    fun transitionAcrossMidnightStillWorks() {
        // Night from 23:50: the ramp spans 23:35–00:05.
        assertEquals(0.5f, NightSchedule.nightFactor(23 * 60 + 50, 23 * 60 + 50, 6 * 60, 30), 0.001f)
        assertEquals(5f / 6f, NightSchedule.nightFactor(0, 23 * 60 + 50, 6 * 60, 30), 0.001f)
    }

    @Test
    fun sameStartAndEndMeansNoNight() {
        assertEquals(0f, NightSchedule.nightFactor(600, 600, 600, 30), 0f)
    }

    @Test
    fun blendInterpolatesWarmth() {
        assertEquals(10, NightSchedule.blend(10, 60, 0f))
        assertEquals(35, NightSchedule.blend(10, 60, 0.5f))
        assertEquals(60, NightSchedule.blend(10, 60, 1f))
    }
}
