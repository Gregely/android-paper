package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * The "Secure app — Tap here to pause for 5 mins" button shown over a secure app's black box.
 *
 * The main overlay passes every touch through (FLAG_NOT_TOUCHABLE), so this is its own small,
 * touchable TYPE_ACCESSIBILITY_OVERLAY window: only taps on the pill itself are caught, and
 * everything around it still reaches the app underneath. It sits near the bottom, clear of
 * the notification shade's pull-down area, and hides itself after a few seconds.
 * Accessibility overlays are never captured, so it doesn't end up in the filtered frame.
 * Main thread only.
 */
class SecurePill(service: AccessibilityService, private val onTap: () -> Unit) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var attached = false

    private val pill = TextView(service).apply {
        setText(R.string.secure_app_message)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        maxLines = 2
        val padH = (22 * density).toInt()
        val padV = (13 * density).toInt()
        setPadding(padH, padV, padH, padV)
        minHeight = (48 * density).toInt()
        isClickable = true
        isFocusable = true
        contentDescription = text
        setOnClickListener {
            hide()
            onTap()
        }
    }

    /** Shows the pill in the page's warmth-tinted ink and paper for [durationMs]. */
    fun show(warmth: Int, durationMs: Long) {
        val ink = EinkFilter.tint(0x33, warmth)
        val paper = EinkFilter.tint(0xF5, warmth)
        pill.setTextColor(paper)
        pill.maxWidth = pill.resources.displayMetrics.widthPixels - (48 * density).toInt()
        val shape = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(ink)
            // A hairline edge so it reads as a button even over the black secure area.
            setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(0x66, Color.red(paper), Color.green(paper), Color.blue(paper)))
        }
        pill.background = RippleDrawable(ColorStateList.valueOf(Color.argb(0x33, 0xFF, 0xFF, 0xFF)), shape, null)

        if (!attached) {
            try {
                windowManager.addView(pill, layoutParams())
                attached = true
            } catch (e: RuntimeException) {
                return // The service is going away; nothing to show on.
            }
        }
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, durationMs)
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        if (!attached) return
        attached = false
        runCatching { windowManager.removeViewImmediate(pill) }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable, but only within its own bounds; never takes focus from the app.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Bottom of the screen, kept above the navigation bar (the default insets fitting),
        // well away from the status bar where the notification shade is pulled down.
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (72 * density).toInt()
        title = "PaperScreen secure app"
    }
}
