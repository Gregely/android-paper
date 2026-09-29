package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunTimesTest {

    private fun hm(h: Int, m: Int) = h * 60 + m

    @Test
    fun londonMidsummer() {
        // 21 June 2026, BST: sunrise ≈ 04:43, sunset ≈ 21:21.
        val r = SunTimes.compute(172, 51.5083, -0.1253, 60)
        assertEquals(hm(4, 43).toDouble(), r.sunrise!!.toDouble(), 6.0)
        assertEquals(hm(21, 21).toDouble(), r.sunset!!.toDouble(), 6.0)
    }

    @Test
    fun newYorkMidwinter() {
        // 21 December 2026, EST: sunrise ≈ 07:17, sunset ≈ 16:32.
        val r = SunTimes.compute(355, 40.7142, -74.0064, -300)
        assertEquals(hm(7, 17).toDouble(), r.sunrise!!.toDouble(), 6.0)
        assertEquals(hm(16, 32).toDouble(), r.sunset!!.toDouble(), 6.0)
    }

    @Test
    fun polarNightAndMidnightSun() {
        val winter = SunTimes.compute(355, 69.65, 18.96, 60)
        assertNull(winter.sunrise)
        assertTrue(winter.polarNight)
        val summer = SunTimes.compute(172, 69.65, 18.96, 120)
        assertNull(summer.sunset)
        assertTrue(!summer.polarNight)
    }
}
