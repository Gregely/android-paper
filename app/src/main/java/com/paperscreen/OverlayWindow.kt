package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
 * filtered too. The window is opaque at all times: captures are taken per window and never
 * include it. Main thread only.
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
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
        title = "PaperScreen"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        fitInsetsSides = 0
    }

    /** Draws the page-turn flash or the current frame at 1:1 screen pixels. */
    class FrameView(context: Context) : View(context) {

        private var frame: Bitmap? = null
        private val location = IntArray(2)
        private val paint = Paint().apply { isFilterBitmap = false }

        init {
            // Keep the overlay out of the accessibility events that drive "refresh on change".
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun showFlash() = showFrame(null)

        /** Shows [bitmap], or the flash when it is null. */
        fun showFrame(bitmap: Bitmap?) {
            frame = bitmap
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bitmap = frame
            if (bitmap == null) {
                canvas.drawColor(FLASH_COLOR)
                return
            }
            // The capture is in display coordinates; undo wherever the window landed.
            getLocationOnScreen(location)
            canvas.drawColor(FLASH_COLOR) // Behind any sliver the frame doesn't cover.
            canvas.drawBitmap(bitmap, -location[0].toFloat(), -location[1].toFloat(), paint)
        }
    }

    companion object {
        val FLASH_COLOR = Color.rgb(0xC8, 0xC8, 0xC8)
        const val FLASH_MS = 80L
    }
}
