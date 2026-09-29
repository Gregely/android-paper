package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The full-screen, touch-transparent overlay that shows the frozen, filtered frame.
 *
 * It is a TYPE_ACCESSIBILITY_OVERLAY window owned by [service]. Unlike app overlays,
 * accessibility overlays are trusted by the input system, so they can be fully opaque and
 * still pass touches through (app overlays are capped at 80% opacity since Android 12).
 * They also sit above the status bar, notification shade and keyboard, so those are shown
 * filtered too. Captures are taken per window and never include it. It's only attached while
 * there's a frame to show, never as a blank page: the service calls [attach] once the first
 * valid capture is ready.
 *
 * The window is opaque, which lets the compositor skip whatever is underneath, except while
 * it has [holes][setHoles]: then it's translucent, and those areas are cleared so the real
 * windows beneath show through. Main thread only.
 */
class OverlayWindow(service: AccessibilityService) {

    // An AccessibilityService's own WindowManager carries the token accessibility overlays need.
    private val windowManager = service.getSystemService(WindowManager::class.java)

    val view = FrameView(service)
    private var attached = false

    val isAttached: Boolean get() = attached

    private var geometry: DisplayGeometry? = null
    private var translucent = false

    /** Adds the window. Throws if the accessibility service is no longer connected. */
    fun attach(geometry: DisplayGeometry) {
        if (attached) return
        this.geometry = geometry
        windowManager.addView(view, layoutParams(geometry))
        attached = true
    }

    fun resize(geometry: DisplayGeometry) {
        this.geometry = geometry
        if (attached) windowManager.updateViewLayout(view, layoutParams(geometry))
    }

    /**
     * Screen areas to leave see-through, showing the real windows beneath (floating windows
     * that couldn't be captured). Empty closes them all.
     */
    fun setHoles(holes: List<Rect>) {
        view.setHoles(holes)
        val needTranslucent = holes.isNotEmpty()
        if (needTranslucent == translucent) return
        translucent = needTranslucent
        val current = geometry
        if (attached && current != null) {
            runCatching { windowManager.updateViewLayout(view, layoutParams(current)) }
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun layoutParams(geometry: DisplayGeometry) = WindowManager.LayoutParams(
        geometry.width,
        geometry.height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        if (translucent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "PaperScreen"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        fitInsetsSides = 0
    }

    /**
     * Draws the current frame at 1:1 screen pixels (over the paper colour, which only shows if
     * the window is ever up without a frame).
     * The frame bitmap is updated in place by the service (partial refreshes), which then
     * calls [frameChanged]. A full refresh briefly draws it with inverted colours.
     */
    class FrameView(context: Context) : View(context) {

        /** The previous content of a changed region, faded out over the new content. */
        class Ghost(val bitmap: Bitmap, val left: Int, val top: Int)

        private var ghosts: List<Ghost> = emptyList()
        private var ghostStart = 0L
        private val ghostPaint = Paint().apply { isFilterBitmap = false }

        /** Starting opacity (0–1) and fade time of partial-refresh ghosts. */
        var ghostAlpha = PaperSettings.DEFAULT_GHOST_OPACITY / 100f
        var ghostFadeMs = PaperSettings.DEFAULT_GHOST_FADE_MS.toLong()

        private var frame: Bitmap? = null
        private var inverted = false
        private var holes: List<Rect> = emptyList()
        private val holePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val location = IntArray(2)
        private val paint = Paint().apply { isFilterBitmap = false }
        private val invertPaint = Paint().apply {
            isFilterBitmap = false
            colorFilter = ColorMatrixColorFilter(INVERT)
        }

        /** Shown where there's no frame yet: the filter's paper colour. */
        var blankColor = Color.WHITE
            set(value) {
                field = value
                invalidate()
            }

        init {
            // Keep the overlay out of the accessibility events that drive "refresh on change".
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        /** Replaces the frame bitmap; null shows a blank page. */
        fun setFrame(bitmap: Bitmap?) {
            frame = bitmap
            inverted = false
            ghosts = emptyList()
            invalidate()
        }

        /** Screen areas drawn fully transparent (the window must be translucent for that). */
        fun setHoles(areas: List<Rect>) {
            if (areas == holes) return
            holes = areas
            invalidate()
        }

        /**
         * Redraws after the frame bitmap's pixels were changed in place. [changed] holds what
         * those regions showed before; it lingers faintly and fades out, the way a real e-ink
         * partial refresh leaves a brief trail of the previous image.
         */
        fun frameChanged(changed: List<Ghost> = emptyList()) {
            ghosts = changed
            ghostStart = SystemClock.uptimeMillis()
            invalidate()
        }

        fun setInverted(invert: Boolean) {
            inverted = invert
            ghosts = emptyList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(blankColor)
            // The capture is in display coordinates; undo wherever the window landed.
            getLocationOnScreen(location)
            frame?.let { bitmap ->
                canvas.drawBitmap(
                    bitmap,
                    -location[0].toFloat(),
                    -location[1].toFloat(),
                    if (inverted) invertPaint else paint,
                )
                drawGhosts(canvas)
            }
            // Last, so nothing is drawn over them: the real window beneath shows through.
            for (hole in holes) {
                canvas.drawRect(
                    (hole.left - location[0]).toFloat(),
                    (hole.top - location[1]).toFloat(),
                    (hole.right - location[0]).toFloat(),
                    (hole.bottom - location[1]).toFloat(),
                    holePaint,
                )
            }
        }

        private fun drawGhosts(canvas: Canvas) {
            if (ghosts.isEmpty()) return
            val t = (SystemClock.uptimeMillis() - ghostStart) / ghostFadeMs.toFloat()
            if (t >= 1f) {
                ghosts = emptyList()
                return
            }
            // Ease out: most of the trail is gone within the first ~100 ms.
            val remaining = (1f - t) * (1f - t)
            ghostPaint.alpha = (ghostAlpha * remaining * 255).toInt()
            for (ghost in ghosts) {
                canvas.drawBitmap(
                    ghost.bitmap,
                    (ghost.left - location[0]).toFloat(),
                    (ghost.top - location[1]).toFloat(),
                    ghostPaint,
                )
            }
            postInvalidateOnAnimation()
        }
    }

    companion object {
        /** Swaps light and dark, keeping alpha. */
        val INVERT = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}
