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
import android.util.Log
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
 * within 333 ms, so each capture first waits out that window's interval. A capture that meets
 * a FLAG_SECURE window stops there and is dropped ([Listener.onCaptureRejected]). A small
 * floating window that fails for any other reason (a text-selection toolbar the system won't
 * capture, say) is left out and reported as a hole, so the overlay can show the real window
 * through it; any other window that fails (error, timeout) is left as a black box.
 *
 * [probe] asks only "is any window secure right now?", without compositing anything, so the
 * service can tell when a secure app has gone.
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
         * screen, or is null when nothing changed. [holes] are the screen bounds of small
         * floating windows that couldn't be captured, to be shown through the overlay.
         */
        fun onFrameProcessed(seq: Int, update: Update?, holes: List<Rect>)

        /** Capture or probe [seq] produced nothing usable (no windows reported, or an error). */
        fun onCaptureFailed(seq: Int)

        /**
         * Capture or probe [seq] met a FLAG_SECURE window. Nothing was diffed; the frame on
         * screen is unchanged.
         */
        fun onCaptureRejected(seq: Int)

        /** Probe [seq] found no secure window. */
        fun onProbeClear(seq: Int)
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

    /** Windows that last refused capture as secure; probes try them first. Worker only. */
    private val secureWindowIds = HashSet<Int>()

    /** The holes last reported, so a change can be logged once. Worker only. */
    private var lastHoles: List<Rect> = emptyList()

    private class Shot(val target: Target, val buffer: HardwareBuffer?, val colorSpace: ColorSpace?)

    private class Job(
        val seq: Int,
        val targets: List<Target>,
        val width: Int,
        val height: Int,
        val probe: Boolean,
    ) {
        val shots = ArrayList<Shot>(targets.size)
        var retried = false
        var sawSecureWindow = false

        fun release() {
            shots.forEach { it.buffer?.close() }
            shots.clear()
        }
    }

    /**
     * Captures [targets] (any order) and composites them into a [width]×[height] frame. A
     * capture that meets a secure window is dropped instead ([Listener.onCaptureRejected]).
     */
    fun capture(seq: Int, targets: List<Target>, width: Int, height: Int) = worker.post {
        start(Job(seq, targets.sortedBy { it.layer }, width, height, probe = false))
    }

    /**
     * Checks whether any of [targets] is secure, trying the ones that were secure last time
     * first (for them the answer comes back at once). Reports [Listener.onCaptureRejected]
     * or [Listener.onProbeClear]; nothing is composited.
     */
    fun probe(seq: Int, targets: List<Target>) = worker.post {
        val ordered = targets.sortedWith(compareBy({ it.windowId !in secureWindowIds }, { it.layer }))
        start(Job(seq, ordered, 0, 0, probe = true))
    }

    private fun start(job: Job) {
        activeSeq = job.seq
        if (job.targets.isEmpty()) {
            Log.w(TAG, "capture skipped: no windows reported")
            main.post { listener.onCaptureFailed(job.seq) }
            return
        }
        captureNext(job)
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
        secureWindowIds.clear()
        lastHoles = emptyList()
    }

    private fun captureNext(job: Job) {
        if (job.seq != activeSeq) {
            job.release()
            return
        }
        if (job.sawSecureWindow) {
            // No point capturing the rest: the frame won't be shown.
            job.release()
            val seq = job.seq
            main.post { listener.onCaptureRejected(seq) }
            return
        }
        if (job.shots.size == job.targets.size) {
            if (job.probe) {
                job.release()
                secureWindowIds.clear()
                val seq = job.seq
                main.post { listener.onProbeClear(seq) }
            } else {
                composite(job)
            }
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
                secureWindowIds -= target.windowId
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
                    secureWindowIds += target.windowId
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
            val holes = ArrayList<Rect>()
            for (shot in job.shots) {
                val bitmap = shot.buffer?.let { Bitmap.wrapHardwareBuffer(it, shot.colorSpace) }
                if (bitmap == null) {
                    val bounds = shot.target.bounds
                    if (FrameCheck.isFloatingWindow(bounds.width(), bounds.height(), width, height)) {
                        // Shown through the overlay instead; what's beneath it stays in the frame.
                        holes += Rect(bounds)
                    } else {
                        canvas.drawRect(bounds, failedPaint)
                    }
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
            logHoles(holes)
            process(composite, seq, holes)
        } catch (e: RuntimeException) {
            Log.w(TAG, "capture failed while compositing", e)
            job.release()
            main.post { listener.onCaptureFailed(seq) }
        }
    }

    private fun logHoles(holes: List<Rect>) {
        if (holes == lastHoles) return
        lastHoles = holes
        if (holes.isEmpty()) {
            Log.i(TAG, "all floating windows captured again; overlay holes closed")
        } else {
            Log.i(TAG, "couldn't capture floating window(s) at $holes; showing them through the overlay")
        }
    }

    private fun process(composite: Bitmap, seq: Int, holes: List<Rect>) {
        val width = composite.width
        val height = composite.height
        val count = width * height
        if (pixels.size != count) pixels = IntArray(count)
        composite.getPixels(pixels, 0, width, 0, 0, width, height)
        composite.recycle()

        val params = filterParams
        if (params == null) {
            Log.w(TAG, "capture skipped: no filter settings yet")
            main.post { listener.onCaptureFailed(seq) }
            return
        }
        filter.setParams(params)
        filter.applyToArgb(pixels, count)
        // A single flat colour means the capture didn't really work; showing it would cover
        // the screen with a solid page. Skip it (the frame on screen stays) and try again.
        if (FrameCheck.isUniform(pixels, count)) {
            Log.w(TAG, "capture skipped: the frame came out a single colour (#%06X)".format(pixels[0] and 0xFFFFFF))
            main.post { listener.onCaptureFailed(seq) }
            return
        }
        val update = frameDiff.diff(pixels, width, height)?.let { Update(epoch, width, height, it) }
        main.post { listener.onFrameProcessed(seq, update, holes) }
    }

    private companion object {
        const val TAG = "PaperScreen"
        const val NONE = -1

        /** The system's limit is 333 ms per window; a little slack avoids rejected requests. */
        const val MIN_WINDOW_INTERVAL_MS = 340L
        const val WINDOW_TIMEOUT_MS = 500L
    }
}
