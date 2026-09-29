package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * "Secure app detected — overlay paused": a small, toast-like label near the bottom of the
 * screen while the overlay steps aside for a secure app.
 *
 * It's its own TYPE_ACCESSIBILITY_OVERLAY window, so it shows with the main overlay removed.
 * It's purely informational: not touchable (taps go straight to the app beneath) and never
 * focused. Accessibility overlays aren't captured, so it never ends up in a filtered frame.
 * Main thread only.
 */
class SecureNotice(service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var attached = false

    private val label = TextView(service).apply {
        setText(R.string.secure_app_notice)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        maxLines = 2
        val padH = dp(18)
        val padV = dp(10)
        setPadding(padH, padV, padH, padV)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Shows the label for [durationMs] in dark grey ink on the warmth-tinted paper colour, in
     * the home screen's [typeface]. Showing it again restarts the timer.
     */
    fun show(warmth: Int, typeface: Typeface, durationMs: Long) {
        val ink = EinkFilter.tint(0x33, warmth)
        label.setTextColor(ink)
        label.typeface = typeface
        label.maxWidth = label.resources.displayMetrics.widthPixels - dp(48)
        label.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(EinkFilter.tint(0xF4, warmth))
            // A hairline edge keeps it distinct over a white app.
            setStroke(1, EinkFilter.tint(0xD0, warmth))
        }
        if (!attached) {
            try {
                windowManager.addView(label, layoutParams())
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
        runCatching { windowManager.removeViewImmediate(label) }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Information only: every touch passes through, and it never takes focus.
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Near the bottom like a toast, above the navigation bar.
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(72)
        title = "PaperScreen secure app notice"
    }

    private fun dp(value: Int) = (value * density).toInt()
}
