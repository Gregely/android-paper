package com.paperscreen

/**
 * Sanity checks on a filtered frame before it's shown. A capture that comes out as one flat
 * colour (every window failed, or the GPU read back nothing) would cover the screen with a
 * solid page, so it's treated as a failed capture instead.
 */
object FrameCheck {

    /** Grey levels within which a frame still counts as a single colour. */
    const val UNIFORM_TOLERANCE = 4

    /**
     * Whether all of the first [count] ARGB [pixels] are the same grey, give or take
     * [tolerance]. The filter's output is a tinted grey, so the green channel stands for it.
     * Stops at the first pixel that shows otherwise, so a normal frame is rejected quickly.
     */
    fun isUniform(pixels: IntArray, count: Int, tolerance: Int = UNIFORM_TOLERANCE): Boolean {
        if (count <= 0) return true
        var min = 255
        var max = 0
        for (i in 0 until count) {
            val grey = (pixels[i] ushr 8) and 0xFF
            if (grey < min) min = grey
            if (grey > max) max = grey
            if (max - min > tolerance) return false
        }
        return true
    }
}
