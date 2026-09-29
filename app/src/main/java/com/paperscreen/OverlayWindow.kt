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
import android.graphics.RectF
import android.util.TypedValue
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

    /**
     * Draws the current frame at 1:1 screen pixels, or a blank page before the first one.
     * The frame bitmap is updated in place by the service (partial refreshes), which then
     * calls [frameChanged]. A full refresh briefly draws it with inverted colours.
     */
    class FrameView(context: Context) : View(context) {

        private var frame: Bitmap? = null
        private var inverted = false
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

        private var message: String? = null
        private val clearMessage = Runnable {
            message = null
            invalidate()
        }
        private val density = resources.displayMetrics.density
        private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
            color = MESSAGE_TEXT
            textAlign = Paint.Align.CENTER
        }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MESSAGE_BACKGROUND }
        private val pill = RectF()

        init {
            // Keep the overlay out of the accessibility events that drive "refresh on change".
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        /** Replaces the frame bitmap; null shows a blank page. */
        fun setFrame(bitmap: Bitmap?) {
            frame = bitmap
            inverted = false
            invalidate()
        }

        /** Redraws after the frame bitmap's pixels were changed in place. */
        fun frameChanged() = invalidate()

        fun setInverted(invert: Boolean) {
            inverted = invert
            invalidate()
        }

        /**
         * Shows a short message near the bottom of the overlay. System toasts sit below
         * accessibility overlays, so they'd be hidden behind this opaque one.
         */
        fun showMessage(text: String, durationMs: Long) {
            message = text
            removeCallbacks(clearMessage)
            postDelayed(clearMessage, durationMs)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(blankColor)
            frame?.let { bitmap ->
                // The capture is in display coordinates; undo wherever the window landed.
                getLocationOnScreen(location)
                canvas.drawBitmap(
                    bitmap,
                    -location[0].toFloat(),
                    -location[1].toFloat(),
                    if (inverted) invertPaint else paint,
                )
            }
            message?.let { drawMessage(canvas, it) }
        }

        private fun drawMessage(canvas: Canvas, text: String) {
            val padH = 20 * density
            val padV = 12 * density
            val textWidth = messagePaint.measureText(text)
            val metrics = messagePaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val centerX = width / 2f
            val bottom = height - 96 * density
            pill.set(centerX - textWidth / 2 - padH, bottom - textHeight - 2 * padV, centerX + textWidth / 2 + padH, bottom)
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, pillPaint)
            canvas.drawText(text, centerX, pill.top + padV - metrics.ascent, messagePaint)
        }
    }

    companion object {
        /** How long a full refresh shows the inverted frame. */
        const val INVERT_MS = 80L

        /** Swaps light and dark, keeping alpha. */
        val INVERT = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        private val MESSAGE_BACKGROUND = Color.rgb(0x2B, 0x2A, 0x28)
        private val MESSAGE_TEXT = Color.rgb(0xF2, 0xEE, 0xE6)
    }
}
