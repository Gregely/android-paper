package com.paperscreen

import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.ceil
import kotlin.math.max

/** Size, density and orientation of the physical display, which the capture must match 1:1. */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val refreshRate: Float,
) {
    /** One vsync, rounded up, in milliseconds. */
    val frameMs: Long get() = ceil(1000.0 / refreshRate.coerceIn(30f, 240f)).toLong()

    /**
     * How long to wait after changing the overlay before trusting that the compositor has
     * put the change on screen (and therefore into the capture).
     */
    val settleMs: Long get() = max(30L, frameMs * 3)

    companion object {
        @Suppress("DEPRECATION") // getRealMetrics is the simplest source of real pixels on API 28+.
        fun of(display: Display): DisplayGeometry {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            return DisplayGeometry(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                rotation = display.rotation,
                refreshRate = display.refreshRate,
            )
        }
    }
}
