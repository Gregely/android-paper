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
 * The filtered frame is then diffed against what the overlay shows, and only the rows that
 * changed are handed over as [FrameDiff.Band]s, so static areas are never touched.
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
        /**
         * Capture [seq] is filtered. [update] holds the pixels that differ from the frame on
         * screen, or is null when nothing changed. [secureWindow] is true when a window
         * refused capture because it is FLAG_SECURE.
         */
        fun onFrameProcessed(seq: Int, update: Update?, secureWindow: Boolean)

        /** Capture [seq] produced nothing usable (no windows reported, or an error). */
        fun onCaptureFailed(seq: Int)
    }

    /**
     * The changes that turn the frame on screen into the new one. After a [reset] the first
     * update covers the whole [width]×[height] frame.
     */
    class Update(val epoch: Int, val width: Int, val height: Int, val bands: List<FrameDiff.Band>) {
        /** Writes the changes into [bitmap], which must hold the previous frame. */
        fun applyTo(bitmap: Bitmap) {
            for (band in bands) {
                bitmap.setPixels(band.pixels, 0, band.width, band.left, band.top, band.width, band.height)
            }
        }
    }

    /** A window to capture, from [AccessibilityService.getWindows]. */
    class Target(val windowId: Int, val layer: Int, val bounds: Rect)

    /** Read on [worker] for every processed frame. */
    @Volatile var filterParams: EinkFilter.Params? = null

    // After release the worker is gone; run late screenshot callbacks inline so their
    // buffers are still closed (the abandoned job just releases them).
    private val executor = Executor { if (!worker.post(it)) it.run() }
    private val filter = EinkFilter()
    private val lastCaptureAt = HashMap<Int, Long>()
    private var pixels = IntArray(0)

    private val frameDiff = FrameDiff()
    private var epoch = 0
    private val failedPaint = Paint().apply { color = Color.BLACK }
    @Volatile private var activeSeq = NONE

    private class Shot(val target: Target, val buffer: HardwareBuffer?, val colorSpace: ColorSpace?)

    private class Job(val seq: Int, val targets: List<Target>, val width: Int, val height: Int) {
        val shots = ArrayList<Shot>(targets.size)
        var retried = false
        var sawSecureWindow = false

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

    /**
     * Forgets the frame on screen (the overlay was cleared), so the next update is a whole
     * frame tagged with [newEpoch]. Also abandons any capture in progress.
     */
    fun reset(newEpoch: Int) = worker.post {
        activeSeq = NONE
        epoch = newEpoch
        frameDiff.reset()
    }

    fun release() = worker.post {
        activeSeq = NONE
        pixels = IntArray(0)
        frameDiff.reset()
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
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                    job.sawSecureWindow = true
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
            process(composite, seq, job.sawSecureWindow)
        } catch (e: RuntimeException) {
            job.release()
            main.post { listener.onCaptureFailed(seq) }
        }
    }

    private fun process(composite: Bitmap, seq: Int, secure: Boolean) {
        val width = composite.width
        val height = composite.height
        val count = width * height
        if (pixels.size != count) pixels = IntArray(count)
        composite.getPixels(pixels, 0, width, 0, 0, width, height)
        composite.recycle()

        filterParams?.let(filter::setParams)
        filter.applyToArgb(pixels, count)
        val update = frameDiff.diff(pixels, width, height)?.let { Update(epoch, width, height, it) }
        main.post { listener.onFrameProcessed(seq, update, secure) }
    }

    private companion object {
        const val NONE = -1

        /** The system's limit is 333 ms per window; a little slack avoids rejected requests. */
        const val MIN_WINDOW_INTERVAL_MS = 340L
        const val WINDOW_TIMEOUT_MS = 500L
    }
}
