package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
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
 * filtered too. Main thread only.
 */
class OverlayWindow(service: AccessibilityService) {

    // An AccessibilityService's own WindowManager carries the token accessibility overlays need.
    private val windowManager = service.getSystemService(WindowManager::class.java)

    val view = FrameView(service)
    private var attached = false

    /** Adds the window. Throws if the accessibility service is no longer connected. */
    fun attach(geometry: DisplayGeometry) {
        if (attached) return
        windowManager.addView(view, layoutParams(geometry))
        attached = true
    }

    fun resize(geometry: DisplayGeometry) {
        if (attached) windowManager.updateViewLayout(view, layoutParams(geometry))
    }

    fun detach() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** A GONE root view makes the window manager hide the window entirely. */
    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
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
        // Translucent so the overlay can go clear for a capture. It also keeps the compositor
        // treating the window as see-through, so changes underneath still produce frames for
        // "refresh on change" to notice.
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "PaperScreen"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            fitInsetsSides = 0
        }
    }

    /** Draws either nothing, the page-turn flash, or the current frame at 1:1 screen pixels. */
    class FrameView(context: Context) : View(context) {

        private enum class Mode { CLEAR, FLASH, FRAME }

        private var mode = Mode.CLEAR
        private var frame: Bitmap? = null
        private val location = IntArray(2)
        private val paint = Paint().apply { isFilterBitmap = false }
        private var afterDraw: Runnable? = null

        /** Clear frames still to draw before [blinkClear] puts the flash back. */
        private var blinkFramesLeft = 0
        /** Bumped on every content change, so a stale blink can't undo a newer one. */
        private var generation = 0

        /** Transparent: the real screen shows through. */
        fun showClear() = setMode(Mode.CLEAR)

        fun showFlash() = setMode(Mode.FLASH)

        fun showFrame(bitmap: Bitmap?) {
            frame = bitmap
            setMode(if (bitmap != null) Mode.FRAME else Mode.CLEAR)
        }

        /**
         * Goes transparent for exactly [frames] drawn frames, then back to the flash. The
         * compositor latches one buffer per vsync, so the clear frames reach the screen (and
         * the capture) as a blink in the middle of the flash.
         */
        fun blinkClear(frames: Int) {
            setMode(Mode.CLEAR)
            blinkFramesLeft = frames.coerceAtLeast(1)
        }

        /** Redraws the current content, forcing the compositor to produce a fresh frame. */
        fun nudge() = invalidate()

        /** Runs [action] once, just after the next draw pass has been recorded. */
        fun doAfterNextDraw(action: Runnable) {
            afterDraw = action
            invalidate()
        }

        /** Drops a pending [doAfterNextDraw] action. */
        fun cancelAfterDraw() {
            afterDraw = null
        }

        private fun setMode(newMode: Mode) {
            mode = newMode
            blinkFramesLeft = 0
            generation++
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            when (mode) {
                Mode.CLEAR -> canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                Mode.FLASH -> canvas.drawColor(FLASH_COLOR)
                Mode.FRAME -> frame?.let { bitmap ->
                    // The capture is in display coordinates; undo wherever the window landed.
                    getLocationOnScreen(location)
                    canvas.drawBitmap(bitmap, -location[0].toFloat(), -location[1].toFloat(), paint)
                }
            }
            if (blinkFramesLeft > 0) {
                blinkFramesLeft--
                if (blinkFramesLeft > 0) {
                    postInvalidateOnAnimation() // Draw another clear frame on the next vsync.
                } else {
                    val blink = generation
                    // Posted after this draw, so the flash is drawn on the very next vsync.
                    post { if (generation == blink) showFlash() }
                }
            }
            afterDraw?.let {
                afterDraw = null
                post(it)
            }
        }
    }

    companion object {
        val FLASH_COLOR = Color.rgb(0xC8, 0xC8, 0xC8)
        const val FLASH_MS = 80L
    }
}
