package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkFilterTest {

    private fun lut(warmth: Int = 0, contrast: Int = 50, levels: Int = 16) =
        IntArray(256).also { EinkFilter.buildLut(EinkFilter.Params(warmth, contrast, levels), it) }

    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test
    fun defaultContrastMapsToSoftInkAndPaper() {
        assertEquals(30, EinkFilter.inkLevel(50))
        assertEquals(225, EinkFilter.paperLevel(50))
        assertEquals(0, EinkFilter.inkLevel(100))
        assertEquals(255, EinkFilter.paperLevel(100))
    }

    @Test
    fun posterizesToRequestedLevelCount() {
        for (levels in listOf(4, 16, 32)) {
            val distinct = lut(levels = levels).map { green(it) }.toSet()
            assertEquals("levels=$levels", levels, distinct.size)
        }
    }

    @Test
    fun posterizationOffIsSmooth() {
        // 256 levels (posterization off) keeps every distinct grey the ink..paper range holds.
        val distinct = lut(levels = PaperSettings.SMOOTH_LEVELS).map { green(it) }.toSet()
        val range = EinkFilter.paperLevel(50) - EinkFilter.inkLevel(50)
        assertTrue("got ${distinct.size} greys", distinct.size >= range * 9 / 10)
    }

    @Test
    fun outputIsOpaqueAndMonotonic() {
        val table = lut(warmth = 20)
        for (i in 1..255) {
            assertEquals(0xFF, table[i] ushr 24)
            assertTrue(green(table[i]) >= green(table[i - 1]))
        }
    }

    @Test
    fun warmthShiftsFromCoolToAmber() {
        val paperCool = lut(warmth = 0)[255]
        val paperWarm = lut(warmth = 100)[255]
        assertTrue("cool paper leans blue", blue(paperCool) > red(paperCool))
        assertTrue("warm paper leans amber", red(paperWarm) > green(paperWarm))
        assertTrue("warm paper leans amber", green(paperWarm) > blue(paperWarm))
    }

    @Test
    fun rgbaAndArgbPathsAgree() {
        val filter = EinkFilter().apply { setParams(EinkFilter.Params(20, 50, 16)) }
        // The same colour, #3366CC, in both memory layouts.
        val rgba = intArrayOf(0xFFCC6633.toInt())
        val argb = intArrayOf(0xFF3366CC.toInt())
        filter.applyToRgba(rgba, 1)
        filter.applyToArgb(argb, 1)
        assertEquals(argb[0], rgba[0])
    }

    @Test
    fun checksumTracksContent() {
        val filter = EinkFilter().apply { setParams(EinkFilter.Params(20, 50, 16)) }
        val a = filter.applyToArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2)
        val b = filter.applyToArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2)
        val c = filter.applyToArgb(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 2)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
