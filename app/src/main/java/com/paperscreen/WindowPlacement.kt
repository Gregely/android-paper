package com.paperscreen

/**
 * Where to draw one window's screenshot in screen coordinates.
 *
 * A window screenshot covers the window's whole surface, but the only position the
 * accessibility API reports is the bounds of the window's *touchable* region. The two
 * usually match, but not always. The surface can be larger (a dialog's shadow insets), or the
 * touchable region can be only part of it (a keyboard window taller than its keys). So the
 * screenshot is anchored to whichever edges the evidence supports:
 *
 * - same size as the bounds on an axis: use the bounds,
 * - as large as the display on an axis: start at 0,
 * - bounds touching a display edge: anchor to that edge (status bar, navigation bar, IME),
 * - otherwise: centre on the bounds (surface insets are symmetric).
 */
object WindowPlacement {

    /** Returns the top-left corner as `x to y`. */
    fun origin(
        shotWidth: Int,
        shotHeight: Int,
        boundsLeft: Int,
        boundsTop: Int,
        boundsRight: Int,
        boundsBottom: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int> {
        val x = axis(shotWidth, boundsLeft, boundsRight, displayWidth)
        val y = axis(shotHeight, boundsTop, boundsBottom, displayHeight)
        return x to y
    }

    private fun axis(shot: Int, start: Int, end: Int, display: Int): Int = when {
        shot == end - start -> start
        shot >= display -> 0
        end >= display -> display - shot
        start <= 0 -> 0
        else -> (start + end) / 2 - shot / 2
    }
}
