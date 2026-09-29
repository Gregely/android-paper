package com.paperscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowPlacementTest {

    private val w = 1080
    private val h = 2400

    private fun origin(shotW: Int, shotH: Int, l: Int, t: Int, r: Int, b: Int) =
        WindowPlacement.origin(shotW, shotH, l, t, r, b, w, h)

    @Test
    fun fullScreenAppWindowWithPartlyCoveredBoundsStartsAtOrigin() {
        // Touchable bounds exclude the status and navigation bars; the surface doesn't.
        assertEquals(0 to 0, origin(w, h, 0, 120, w, 2280))
    }

    @Test
    fun exactBoundsAreUsedAsIs() {
        assertEquals(40 to 900, origin(1000, 600, 40, 900, 1040, 1500))
    }

    @Test
    fun statusBarAnchorsToTop() {
        assertEquals(0 to 0, origin(w, 120, 0, 0, w, 100))
    }

    @Test
    fun keyboardTallerThanItsKeysAnchorsToBottom() {
        // IME surface 1200 px tall, touchable keys only in the bottom 900 px.
        assertEquals(0 to h - 1200, origin(w, 1200, 0, 1500, w, h))
    }

    @Test
    fun dialogWithShadowInsetsIsCentredOnItsBounds() {
        // 40 px of surface insets on every side of an 800×500 dialog.
        assertEquals(100 to 910, origin(880, 580, 140, 950, 940, 1450))
    }

    @Test
    fun landscapeNavBarAnchorsToRightEdge() {
        assertEquals(w - 150 to 0, origin(150, h, w - 130, 0, w, h))
    }
}
