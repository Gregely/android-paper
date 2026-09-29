package com.paperscreen

/**
 * Tracks the frame on screen and works out which pixels a new frame changes, so partial
 * refreshes only touch those. Changed rows are grouped into [Band]s; each band spans the
 * leftmost to rightmost changed pixel of its rows, so pixels inside a band but outside the
 * change are rewritten with identical values. Static areas are never modified.
 *
 * Not thread-safe: use from one thread.
 */
class FrameDiff {

    /** A rectangle of new pixels, row-major. */
    class Band(val left: Int, val top: Int, val width: Int, val height: Int, val pixels: IntArray)

    /** A copy of the frame on screen; empty until the first frame. */
    private var shown = IntArray(0)
    private var shownWidth = 0

    /** Forgets the frame on screen, so the next [diff] returns the whole frame. */
    fun reset() {
        shown = IntArray(0)
        shownWidth = 0
    }

    /**
     * Returns the bands where [pixels] differ from the frame on screen and records [pixels]
     * as the new frame on screen, or null when nothing changed. After a [reset] (or a size
     * change) the result is one band covering the whole frame.
     */
    fun diff(pixels: IntArray, width: Int, height: Int): List<Band>? {
        val count = width * height
        if (shown.size != count || shownWidth != width) {
            shown = pixels.copyOf(count)
            shownWidth = width
            return listOf(Band(0, 0, width, height, pixels.copyOf(count)))
        }
        val shown = shown
        val bands = ArrayList<Band>()
        var bandTop = -1
        var bandBottom = -1
        var bandLeft = width
        var bandRight = -1
        for (y in 0 until height) {
            val row = y * width
            var first = 0
            while (first < width && pixels[row + first] == shown[row + first]) first++
            if (first == width) {
                // Close the band once the gap is wide enough that merging isn't worth it.
                if (bandTop >= 0 && y - bandBottom > BAND_GAP_ROWS) {
                    bands += cut(pixels, bandLeft, bandTop, bandRight, bandBottom, width)
                    bandTop = -1
                }
                continue
            }
            var last = width - 1
            while (pixels[row + last] == shown[row + last]) last--
            if (bandTop < 0) {
                bandTop = y
                bandLeft = first
                bandRight = last
            } else {
                bandLeft = minOf(bandLeft, first)
                bandRight = maxOf(bandRight, last)
            }
            bandBottom = y
        }
        if (bandTop >= 0) bands += cut(pixels, bandLeft, bandTop, bandRight, bandBottom, width)
        return bands.ifEmpty { null }
    }

    /** Copies a rectangle (inclusive bounds) out of [pixels] and into [shown]. */
    private fun cut(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, stride: Int): Band {
        val w = right - left + 1
        val h = bottom - top + 1
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val from = (top + y) * stride + left
            System.arraycopy(pixels, from, out, y * w, w)
            System.arraycopy(pixels, from, shown, from, w)
        }
        return Band(left, top, w, h, out)
    }

    private companion object {
        /** Unchanged rows allowed inside one band before it is split in two. */
        const val BAND_GAP_ROWS = 32
    }
}
