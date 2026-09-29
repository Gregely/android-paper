package com.paperscreen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * "Hold to preview": snapshots the activity's own window, runs it through the same
 * [EinkFilter] the service uses, and lays the result over the window (with the full-refresh
 * inversion, if full refreshes are on) until [end] is called.
 */
class FilterPreview(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("PaperScreen-preview").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val filter = EinkFilter() // Worker thread only.

    private var cover: ImageView? = null
    /** Incremented on every begin/end so late callbacks from an old preview are ignored. */
    private var token = 0

    /** [invertMs] is the full-refresh flash duration, or 0 when full refreshes are off. */
    fun begin(params: EinkFilter.Params, invertMs: Long) {
        if (cover != null) return
        val decor = activity.window.decorView as ViewGroup
        val width = decor.width
        val height = decor.height
        if (width == 0 || height == 0) return
        val current = ++token
        val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, snapshot, { result ->
            if (result != PixelCopy.SUCCESS) return@request
            val pixels = IntArray(width * height)
            snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
            filter.setParams(params)
            filter.applyToArgb(pixels, pixels.size)
            snapshot.setPixels(pixels, 0, width, 0, 0, width, height)
            main.post { if (current == token) showFiltered(snapshot, current, invertMs) }
        }, worker)
    }

    fun end() {
        token++
        cover?.let { (activity.window.decorView as ViewGroup).removeView(it) }
        cover = null
    }

    fun release() {
        end()
        workerThread.quitSafely()
    }

    /** Shows the filtered snapshot the way a full refresh does: inverted briefly, then normal. */
    private fun showFiltered(bitmap: Bitmap, current: Int, invertMs: Long) {
        if (cover != null) return
        val view = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            translationZ = 1000f
            setImageBitmap(bitmap)
            if (invertMs > 0) colorFilter = ColorMatrixColorFilter(OverlayWindow.INVERT)
        }
        (activity.window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        cover = view
        if (invertMs > 0) {
            main.postDelayed({
                if (current == token) view.colorFilter = null
            }, invertMs)
        }
    }
}
