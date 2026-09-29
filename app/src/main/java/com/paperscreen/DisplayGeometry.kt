package com.paperscreen

import android.util.DisplayMetrics
import android.view.Display

/** Size, density and orientation of the physical display, which the frame must match 1:1. */
data class DisplayGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
) {
    companion object {
        @Suppress("DEPRECATION") // getRealMetrics is the simplest source of real pixels.
        fun of(display: Display): DisplayGeometry {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            return DisplayGeometry(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                rotation = display.rotation,
            )
        }
    }
}
