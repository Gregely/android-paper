package com.paperscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCheckTest {

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun solidFrameIsUniform() {
        val paper = EinkFilter.paperColor(EinkFilter.Params(20, 50, 16))
        assertTrue(FrameCheck.isUniform(IntArray(1000) { paper }, 1000))
        assertTrue(FrameCheck.isUniform(IntArray(1000) { grey(0) }, 1000))
    }

    @Test
    fun nearlySolidFrameIsUniform() {
        val pixels = IntArray(1000) { grey(200 + it % (FrameCheck.UNIFORM_TOLERANCE + 1)) }
        assertTrue(FrameCheck.isUniform(pixels, pixels.size))
    }

    @Test
    fun anyRealDetailIsNotUniform() {
        // A white page with a single dark pixel of text at the very end still counts.
        val pixels = IntArray(10_000) { grey(225) }
        pixels[pixels.lastIndex] = grey(30)
        assertFalse(FrameCheck.isUniform(pixels, pixels.size))
    }

    @Test
    fun onlyTheFirstCountPixelsAreChecked() {
        // The pixel buffer is reused and can be longer than the frame.
        val pixels = IntArray(100) { grey(128) }
        pixels[99] = grey(0)
        assertTrue(FrameCheck.isUniform(pixels, 99))
        assertTrue(FrameCheck.isUniform(pixels, 0))
    }
}
