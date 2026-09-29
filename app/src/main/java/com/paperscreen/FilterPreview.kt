package com.paperscreen

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max

/**
 * "Hold to preview": snapshots the activity's own window, runs it through the same
 * [EinkFilter] the service uses, and lays the result over the window (with the page-turn
 * flash) until [end] is called.
 */
class FilterPreview(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("PaperScreen-preview").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val filter = EinkFilter() // Worker thread only.

    private var cover: ImageView? = null
    private var flashStart = 0L
    /** Incremented on every begin/end so late callbacks from an old preview are ignored. */
    private var token = 0

    fun begin(params: EinkFilter.Params) {
        if (cover != null) return
        val decor = activity.window.decorView as ViewGroup
        val width = decor.width
        val height = decor.height
        if (width == 0 || height == 0) return
        val current = ++token
        val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, snapshot, { result ->
            if (result != PixelCopy.SUCCESS) return@request
            main.post { if (current == token) showFlash() }
            val pixels = IntArray(width * height)
            snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
            filter.setParams(params)
            filter.applyToArgb(pixels, pixels.size)
            snapshot.setPixels(pixels, 0, width, 0, 0, width, height)
            main.post { if (current == token) showFiltered(snapshot, current) }
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

    private fun showFlash() {
        if (cover != null) return
        val view = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(OverlayWindow.FLASH_COLOR)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            translationZ = 1000f
        }
        (activity.window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        cover = view
        flashStart = SystemClock.uptimeMillis()
    }

    private fun showFiltered(bitmap: Bitmap, current: Int) {
        val remaining = max(0L, flashStart + OverlayWindow.FLASH_MS - SystemClock.uptimeMillis())
        main.postDelayed({
            if (current == token) cover?.setImageBitmap(bitmap)
        }, remaining)
    }
}
