package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor

/**
 * Rebuilds the screen from per-window screenshots and turns it into a filtered bitmap.
 *
 * Each window below our overlay is captured with [AccessibilityService.takeScreenshotOfWindow],
 * which records only that window's own layers, so the overlay is never in the picture and
 * never has to get out of the way. The shots are composited bottom-up over blank paper (the
 * wallpaper isn't one of the reported windows) on the GPU, read back once, and filtered.
 *
 * Windows are captured one at a time. The system refuses a second capture of the same window
 * within 333 ms, so each capture first waits out that window's interval. A window that can't
 * be captured (FLAG_SECURE, error, timeout) is left as a black box over its bounds.
 *
 * Public methods may be called from any thread; all state lives on [worker]. Listener
 * callbacks are delivered on [main].
 */
class WindowCapturer(
    private val service: AccessibilityService,
    private val worker: Handler,
    private val main: Handler,
    private val listener: Listener,
) {

    interface Listener {
        /** Capture [seq] is filtered. [frame] is null when it is identical to [displayed]. */
        fun onFrameProcessed(seq: Int, frame: Frame?)

        /** Capture [seq] produced nothing usable (no windows reported, or an error). */
        fun onCaptureFailed(seq: Int)
    }

    class Frame(val bitmap: Bitmap, val checksum: Long)

    /** A window to capture, from [AccessibilityService.getWindows]. */
    class Target(val windowId: Int, val layer: Int, val bounds: Rect)

    /** Read on [worker] for every processed frame. */
    @Volatile var filterParams: EinkFilter.Params? = null

    /** The frame the overlay currently shows; never written to and used to detect no-op refreshes. */
    @Volatile var displayed: Frame? = null

    private val executor = Executor { worker.post(it) }
    private val filter = EinkFilter()
    private val lastCaptureAt = HashMap<Int, Long>()
    private var pixels = IntArray(0)
    private val buffers = arrayOfNulls<Bitmap>(2)
    private val failedPaint = Paint().apply { color = Color.BLACK }
    private var activeSeq = NONE

    private class Shot(val target: Target, val buffer: HardwareBuffer?, val colorSpace: ColorSpace?)

    private class Job(val seq: Int, val targets: List<Target>, val width: Int, val height: Int) {
        val shots = ArrayList<Shot>(targets.size)
        var retried = false

        fun release() {
            shots.forEach { it.buffer?.close() }
            shots.clear()
        }
    }

    /** Captures [targets] (any order) and composites them into a [width]×[height] frame. */
    fun capture(seq: Int, targets: List<Target>, width: Int, height: Int) = worker.post {
        activeSeq = seq
        if (targets.isEmpty()) {
            main.post { listener.onCaptureFailed(seq) }
            return@post
        }
        captureNext(Job(seq, targets.sortedBy { it.layer }, width, height))
    }

    /** Abandons any capture in progress. */
    fun cancel() = worker.post { activeSeq = NONE }

    fun release() = worker.post {
        activeSeq = NONE
        buffers.fill(null)
        pixels = IntArray(0)
        lastCaptureAt.clear()
    }

    private fun captureNext(job: Job) {
        if (job.seq != activeSeq) {
            job.release()
            return
        }
        if (job.shots.size == job.targets.size) {
            composite(job)
            return
        }
        val target = job.targets[job.shots.size]
        val now = SystemClock.uptimeMillis()
        val wait = (lastCaptureAt[target.windowId] ?: 0L) + MIN_WINDOW_INTERVAL_MS - now
        if (wait > 0) {
            worker.postDelayed({ captureNext(job) }, wait)
            return
        }
        lastCaptureAt[target.windowId] = now

        var settled = false
        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            job.shots += Shot(target, null, null)
            captureNext(job)
        }
        worker.postDelayed(timeout, WINDOW_TIMEOUT_MS)

        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                if (settled) {
                    result.hardwareBuffer.close()
                    return
                }
                settled = true
                worker.removeCallbacks(timeout)
                job.shots += Shot(target, result.hardwareBuffer, result.colorSpace)
                captureNext(job)
            }

            override fun onFailure(errorCode: Int) {
                if (settled) return
                settled = true
                worker.removeCallbacks(timeout)
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !job.retried) {
                    // Our timing and the system's disagreed by a hair; wait one full interval.
                    job.retried = true
                    lastCaptureAt[target.windowId] = SystemClock.uptimeMillis()
                    captureNext(job)
                    return
                }
                job.shots += Shot(target, null, null) // Secure window, gone, or an error.
                captureNext(job)
            }
        }
        try {
            service.takeScreenshotOfWindow(target.windowId, executor, callback)
        } catch (e: RuntimeException) {
            callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        }
    }

    private fun composite(job: Job) {
        val seq = job.seq
        try {
            val width = job.width
            val height = job.height
            val picture = Picture()
            val canvas = picture.beginRecording(width, height)
            // The wallpaper is never among the reported windows; start from white, which the
            // filter turns into the paper colour.
            canvas.drawColor(Color.WHITE)
            for (shot in job.shots) {
                val bitmap = shot.buffer?.let { Bitmap.wrapHardwareBuffer(it, shot.colorSpace) }
                if (bitmap == null) {
                    canvas.drawRect(shot.target.bounds, failedPaint)
                    continue
                }
                val bounds = shot.target.bounds
                val (x, y) = WindowPlacement.origin(
                    bitmap.width, bitmap.height,
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    width, height,
                )
                canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)
            }
            picture.endRecording()
            // Window shots are hardware bitmaps, so this composites on the GPU and reads back once.
            val composite = Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
            job.release()
            process(composite, seq)
        } catch (e: RuntimeException) {
            job.release()
            main.post { listener.onCaptureFailed(seq) }
        }
    }

    private fun process(composite: Bitmap, seq: Int) {
        val width = composite.width
        val height = composite.height
        val count = width * height
        if (pixels.size != count) pixels = IntArray(count)
        composite.getPixels(pixels, 0, width, 0, 0, width, height)
        composite.recycle()

        filterParams?.let(filter::setParams)
        val checksum = filter.applyToArgb(pixels, count) * 31 + (width.toLong() shl 20) + height
        val shown = displayed
        if (shown != null && shown.checksum == checksum) {
            main.post { listener.onFrameProcessed(seq, null) }
            return
        }
        val bitmap = backBuffer(width, height, shown?.bitmap)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val frame = Frame(bitmap, checksum)
        main.post { listener.onFrameProcessed(seq, frame) }
    }

    /** A bitmap of the right size that the overlay is not currently drawing. */
    private fun backBuffer(width: Int, height: Int, inUse: Bitmap?): Bitmap {
        for (i in buffers.indices) {
            var bitmap = buffers[i]
            if (bitmap != null && (bitmap.width != width || bitmap.height != height)) {
                bitmap = null
                buffers[i] = null
            }
            if (bitmap != null && bitmap === inUse) continue
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setHasAlpha(false)
                buffers[i] = bitmap
            }
            return bitmap
        }
        error("unreachable: at most one buffer can be in use")
    }

    private companion object {
        const val NONE = -1

        /** The system's limit is 333 ms per window; a little slack avoids rejected requests. */
        const val MIN_WINDOW_INTERVAL_MS = 340L
        const val WINDOW_TIMEOUT_MS = 500L
    }
}
