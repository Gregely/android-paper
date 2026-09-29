package com.paperscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameDiffTest {

    private val w = 100
    private val h = 200

    private fun frame(fill: Int = 0) = IntArray(w * h) { fill }

    /** Applies bands to [target] the way the overlay applies them to its bitmap. */
    private fun apply(bands: List<FrameDiff.Band>, target: IntArray) {
        for (b in bands) {
            for (y in 0 until b.height) {
                System.arraycopy(b.pixels, y * b.width, target, (b.top + y) * w + b.left, b.width)
            }
        }
    }

    @Test
    fun firstFrameIsWhole() {
        val bands = FrameDiff().diff(frame(7), w, h)!!
        assertEquals(1, bands.size)
        assertEquals(w, bands[0].width)
        assertEquals(h, bands[0].height)
    }

    @Test
    fun identicalFrameIsNoChange() {
        val diff = FrameDiff()
        diff.diff(frame(7), w, h)
        assertNull(diff.diff(frame(7), w, h))
    }

    @Test
    fun smallChangeIsATightBand() {
        val diff = FrameDiff()
        diff.diff(frame(), w, h)
        val next = frame().also { it[50 * w + 10] = 1; it[52 * w + 20] = 1 }
        val band = diff.diff(next, w, h)!!.single()
        assertEquals(10, band.left)
        assertEquals(50, band.top)
        assertEquals(11, band.width)
        assertEquals(3, band.height)
    }

    @Test
    fun distantChangesAreSeparateBands() {
        val diff = FrameDiff()
        diff.diff(frame(), w, h)
        val next = frame().also { it[5 * w + 1] = 1; it[150 * w + 1] = 1 }
        assertEquals(2, diff.diff(next, w, h)!!.size)
    }

    @Test
    fun applyingBandsReproducesTheNewFrame() {
        val diff = FrameDiff()
        val screen = frame(3)
        diff.diff(screen, w, h)
        val next = frame(3).also {
            for (i in 0 until w) it[10 * w + i] = 9
            it[120 * w + 99] = 4
            it[199 * w] = 5
        }
        apply(diff.diff(next, w, h)!!, screen)
        assertArrayEquals(next, screen)
        // And the next diff is taken against the updated frame.
        assertNull(diff.diff(next, w, h))
    }

    @Test
    fun resetForcesAWholeFrame() {
        val diff = FrameDiff()
        diff.diff(frame(), w, h)
        diff.reset()
        assertEquals(w * h, diff.diff(frame(), w, h)!!.single().pixels.size)
    }
}
