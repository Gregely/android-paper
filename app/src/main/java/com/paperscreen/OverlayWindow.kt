package com.paperscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.hardware.input.InputManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The full-screen, touch-transparent overlay that shows the frozen, filtered frame.
 *
 * TYPE_APPLICATION_OVERLAY always sits below the status bar, notification shade, IME, and
 * system dialogs, so none of those are blocked. Main thread only.
 */
class OverlayWindow(context: Context, display: Display) {

    private val windowContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val inputManager = context.getSystemService(InputManager::class.java)

    val view = FrameView(windowContext)
    private var attached = false

    /** Adds the window. Throws if the overlay permission is missing or was revoked. */
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
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
        // Since Android 12, touches only pass through another app's overlay if the overlay is
        // at most this opaque (0.8 by default); any more and the system swallows them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alpha = inputManager.maximumObscuringOpacityForTouch.coerceIn(0f, 1f)
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

        /** Transparent, so the next capture sees the real screen and not this overlay. */
        fun showClear() {
            mode = Mode.CLEAR
            invalidate()
        }

        fun showFlash() {
            mode = Mode.FLASH
            invalidate()
        }

        fun showFrame(bitmap: Bitmap?) {
            frame = bitmap
            mode = if (bitmap != null) Mode.FRAME else Mode.CLEAR
            invalidate()
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
