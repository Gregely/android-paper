package com.paperscreen

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.SystemClock
import java.nio.ByteOrder

/**
 * Mirrors the screen into an [ImageReader] and turns single frames into filtered bitmaps.
 *
 * The capture always includes our own overlay, so the service clears the overlay before
 * asking for a frame. Between grabs the reader's surface is either detached (no compositing
 * cost) or used only as a cheap "something on screen changed" signal.
 *
 * Public methods may be called from any thread; all state lives on [worker]. Listener
 * callbacks are delivered on [main].
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val worker: Handler,
    private val main: Handler,
    private val listener: Listener,
) : ImageReader.OnImageAvailableListener {

    interface Listener {
        /** A frame arrived while watching: the screen content changed. */
        fun onContentChanged()

        /** Frame [seq] was grabbed; the overlay may be shown again. */
        fun onFrameCaptured(seq: Int)

        /** Frame [seq] is filtered. [frame] is null when it is identical to [displayed]. */
        fun onFrameProcessed(seq: Int, frame: Frame?)

        /**
         * Frame [seq] was entirely black: the system blanks FLAG_SECURE windows (banking apps,
         * DRM video) in captures, so there is nothing meaningful to freeze.
         */
        fun onFrameBlank(seq: Int)

        /** No usable frame arrived for grab [seq] (may follow [onFrameCaptured]). */
        fun onCaptureTimedOut(seq: Int)
    }

    class Frame(val bitmap: Bitmap, val checksum: Long)

    private enum class Mode { IDLE, PREPARING, GRABBING, WATCHING }

    /** Read on [worker] for every processed frame. */
    @Volatile var filterParams: EinkFilter.Params? = null

    /** The frame the overlay currently shows; never written to and used to detect no-op refreshes. */
    @Volatile var displayed: Frame? = null

    private val filter = EinkFilter()
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceAttached = false
    private var mode = Mode.IDLE
    private var grabSeq = 0
    private var watchFromUptime = 0L
    private var pixels = IntArray(0)
    private val buffers = arrayOfNulls<Bitmap>(2)

    fun start(geometry: DisplayGeometry) = worker.post {
        reader = newReader(geometry)
        // Created without a surface: nothing is composited until a surface is attached.
        virtualDisplay = projection.createVirtualDisplay(
            "PaperScreen",
            geometry.width,
            geometry.height,
            geometry.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            null,
        )
    }

    fun resize(geometry: DisplayGeometry) = worker.post {
        val display = virtualDisplay ?: return@post
        val needsSurface = surfaceAttached
        detach()
        reader?.close()
        reader = newReader(geometry)
        display.resize(geometry.width, geometry.height, geometry.densityDpi)
        if (needsSurface) attach()
    }

    /** Starts compositing into the reader; frames are dropped until [grab]. */
    fun prepareGrab() = worker.post {
        mode = Mode.PREPARING
        attach()
    }

    /** Takes the next frame that arrives, or reports a timeout. */
    fun grab(seq: Int, timeoutMs: Long) = worker.post {
        // Anything already queued may predate the overlay being cleared.
        drain()
        mode = Mode.GRABBING
        grabSeq = seq
        attach()
        worker.postDelayed({
            if (mode == Mode.GRABBING && grabSeq == seq) {
                mode = Mode.IDLE
                detach()
                main.post { listener.onCaptureTimedOut(seq) }
            }
        }, timeoutMs)
    }

    /**
     * Reports the first frame composited after [ignoreMs] from now, i.e. the first time
     * anything on screen changes once our own redraws have settled.
     */
    fun watch(ignoreMs: Long) = worker.post {
        mode = Mode.WATCHING
        watchFromUptime = SystemClock.uptimeMillis() + ignoreMs
        attach()
    }

    fun idle() = worker.post {
        mode = Mode.IDLE
        detach()
    }

    fun release() = worker.post {
        mode = Mode.IDLE
        virtualDisplay?.release()
        virtualDisplay = null
        surfaceAttached = false
        reader?.close()
        reader = null
        buffers.fill(null)
        pixels = IntArray(0)
    }

    override fun onImageAvailable(source: ImageReader) {
        val image = acquireLatest(source) ?: return
        if (source !== reader) {
            image.close()
            return
        }
        when (mode) {
            Mode.GRABBING -> {
                mode = Mode.IDLE
                detach()
                val seq = grabSeq
                try {
                    process(image, seq)
                } catch (e: RuntimeException) {
                    main.post { listener.onCaptureTimedOut(seq) }
                }
            }
            Mode.WATCHING -> {
                image.close()
                if (SystemClock.uptimeMillis() >= watchFromUptime) {
                    mode = Mode.IDLE
                    detach()
                    main.post { listener.onContentChanged() }
                }
            }
            Mode.IDLE, Mode.PREPARING -> image.close()
        }
    }

    private fun process(image: Image, seq: Int) {
        val width = image.width
        val height = image.height
        val count = width * height
        try {
            readPixels(image, width, height)
        } finally {
            image.close()
        }
        if (isAllBlack(count)) {
            main.post { listener.onFrameBlank(seq) }
            return
        }
        // The overlay can come back (as the page-turn flash) while we filter.
        main.post { listener.onFrameCaptured(seq) }

        filterParams?.let(filter::setParams)
        val checksum = filter.applyToRgba(pixels, count) * 31 + (width.toLong() shl 20) + height
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

    private fun readPixels(image: Image, width: Int, height: Int) {
        val count = width * height
        if (pixels.size != count) pixels = IntArray(count)
        val plane = image.planes[0]
        // RGBA bytes read as little-endian ints are 0xAABBGGRR, which EinkFilter expects.
        val ints = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val rowInts = plane.rowStride / 4
        if (rowInts == width) {
            ints.get(pixels, 0, count)
        } else {
            for (y in 0 until height) {
                ints.position(y * rowInts)
                ints.get(pixels, y * width, width)
            }
        }
    }

    /** Stops at the first lit pixel, so ordinary frames cost almost nothing. */
    private fun isAllBlack(count: Int): Boolean {
        val pixels = pixels
        for (i in 0 until count) {
            if (pixels[i] and 0x00FFFFFF != 0) return false
        }
        return true
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

    private fun newReader(geometry: DisplayGeometry): ImageReader =
        ImageReader.newInstance(geometry.width, geometry.height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener(this@ScreenCapturer, worker)
        }

    private fun attach() {
        if (surfaceAttached) return
        val display = virtualDisplay ?: return
        val surface = reader?.surface ?: return
        display.surface = surface
        surfaceAttached = true
    }

    private fun detach() {
        if (!surfaceAttached) return
        virtualDisplay?.surface = null
        surfaceAttached = false
    }

    private fun drain() {
        reader?.let { acquireLatest(it)?.close() }
    }

    private fun acquireLatest(source: ImageReader): Image? =
        try {
            source.acquireLatestImage()
        } catch (e: IllegalStateException) {
            // Closed reader, or too many images acquired; either way there's nothing to use.
            null
        }
}
