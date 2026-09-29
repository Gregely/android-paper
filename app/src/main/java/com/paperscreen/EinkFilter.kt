package com.paperscreen

import kotlin.math.roundToInt

/**
 * The e-ink look, folded into a 256-entry lookup table from luma to the final opaque colour:
 *
 * 1. desaturate to luma (Rec. 709 weights),
 * 2. posterize to [Params.greyLevels] evenly spaced steps,
 * 3. squeeze the range from 0..255 into ink..paper (≈30..225 at the default contrast),
 * 4. tint the grey from cool blue-grey (warmth 0) to sepia/amber (warmth 100).
 *
 * Posterizing can't be expressed as a ColorMatrix, but every step above is a function of luma
 * alone, so a table lookup per pixel reproduces the whole chain exactly and cheaply.
 *
 * Not thread-safe: use one instance per thread.
 */
class EinkFilter {

    data class Params(val warmth: Int, val contrast: Int, val greyLevels: Int)

    private val lut = IntArray(256)
    private var built: Params? = null

    fun setParams(params: Params) {
        if (params == built) return
        buildLut(params, lut)
        built = params
    }

    /**
     * Filters pixels read as little-endian ints from an RGBA_8888 buffer (0xAABBGGRR), writing
     * ARGB ints (0xAARRGGBB) back in place. Returns a checksum of the output.
     */
    fun applyToRgba(pixels: IntArray, count: Int): Long {
        val lut = lut
        var hash = 1L
        for (i in 0 until count) {
            val v = pixels[i]
            val r = v and 0xFF
            val g = (v ushr 8) and 0xFF
            val b = (v ushr 16) and 0xFF
            val out = lut[(r * LUMA_R + g * LUMA_G + b * LUMA_B) ushr 8]
            pixels[i] = out
            hash = hash * HASH_PRIME + out
        }
        return hash
    }

    /** Same as [applyToRgba] for ARGB ints, as returned by [android.graphics.Bitmap.getPixels]. */
    fun applyToArgb(pixels: IntArray, count: Int): Long {
        val lut = lut
        var hash = 1L
        for (i in 0 until count) {
            val v = pixels[i]
            val r = (v ushr 16) and 0xFF
            val g = (v ushr 8) and 0xFF
            val b = v and 0xFF
            val out = lut[(r * LUMA_R + g * LUMA_G + b * LUMA_B) ushr 8]
            pixels[i] = out
            hash = hash * HASH_PRIME + out
        }
        return hash
    }

    companion object {
        // Rec. 709 luma in 8.8 fixed point; the weights sum to 256 so white maps to exactly 255.
        private const val LUMA_R = 54
        private const val LUMA_G = 183
        private const val LUMA_B = 19

        private const val HASH_PRIME = 1_000_003L

        // Contrast 0 → washed out, 50 → e-ink's soft 30/225, 100 → full 0/255.
        fun inkLevel(contrast: Int): Int = lerp(60f, 0f, contrast / 100f).roundToInt()
        fun paperLevel(contrast: Int): Int = lerp(195f, 255f, contrast / 100f).roundToInt()

        // Per-channel multipliers for the grey value at warmth 0 (cool) and 100 (amber).
        private val COOL = floatArrayOf(0.96f, 0.99f, 1.04f)
        private val AMBER = floatArrayOf(1.08f, 0.96f, 0.72f)

        /**
         * [grey] (0–255) tinted by [warmth] (0–100) exactly as the filter tints its output, for
         * UI that should match filtered screens (the home screen).
         */
        fun tint(grey: Int, warmth: Int): Int {
            val w = warmth.coerceIn(0, 100) / 100f
            val r = (grey * lerp(COOL[0], AMBER[0], w)).roundToInt().coerceIn(0, 255)
            val g = (grey * lerp(COOL[1], AMBER[1], w)).roundToInt().coerceIn(0, 255)
            val b = (grey * lerp(COOL[2], AMBER[2], w)).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** The colour white maps to: a blank page. */
        fun paperColor(params: Params): Int = IntArray(256).also { buildLut(params, it) }[255]

        fun buildLut(params: Params, out: IntArray) {
            require(out.size == 256)
            val steps = (params.greyLevels.coerceIn(2, 256) - 1).toFloat()
            val ink = inkLevel(params.contrast).toFloat()
            val paper = paperLevel(params.contrast).toFloat()
            val w = params.warmth.coerceIn(0, 100) / 100f
            val mr = lerp(COOL[0], AMBER[0], w)
            val mg = lerp(COOL[1], AMBER[1], w)
            val mb = lerp(COOL[2], AMBER[2], w)
            for (luma in 0..255) {
                val stepped = (luma / 255f * steps).roundToInt() / steps
                val grey = ink + stepped * (paper - ink)
                val r = (grey * mr).roundToInt().coerceIn(0, 255)
                val g = (grey * mg).roundToInt().coerceIn(0, 255)
                val b = (grey * mb).roundToInt().coerceIn(0, 255)
                out[luma] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
