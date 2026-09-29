package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the whole pipeline: per-window capture, filtering, and the full-screen overlay.
 *
 * The overlay is a TYPE_ACCESSIBILITY_OVERLAY window, so it can be fully opaque while
 * touches pass through. The screen underneath is rebuilt from per-window screenshots
 * ([WindowCapturer]), which never include the overlay, so it stays opaque the whole time.
 *
 * Each refresh is one page turn:
 *
 * 1. the overlay flashes to flat grey,
 * 2. meanwhile every window under it is captured, composited and filtered in the background,
 * 3. the new filtered frame replaces the flash (no sooner than ~80 ms after it began).
 *
 * Refreshes happen every interval, or — with "refresh on change" — when accessibility events
 * report that something on screen changed, at most once per interval. Capture pauses while
 * the screen is off or locked.
 */
class PaperScreenAccessibilityService : AccessibilityService(), WindowCapturer.Listener {

    /** What the settings screen can do with the service. */
    enum class State { DISABLED, READY, RUNNING }

    private enum class Phase { PAUSED, IDLE, CAPTURING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: PaperSettings
    private lateinit var displayManager: DisplayManager
    private lateinit var display: Display
    private lateinit var geometry: DisplayGeometry

    // One capture session, from startCapture to stopCapture.
    private var workerThread: HandlerThread? = null
    private var capturer: WindowCapturer? = null
    private var overlay: OverlayWindow? = null

    private var phase = Phase.PAUSED
    /** Incremented whenever an in-flight refresh must be abandoned. */
    private var seq = 0
    private var lastRefreshStart = 0L
    private var flashStart = 0L
    private var displayedFrame: WindowCapturer.Frame? = null

    /** Something changed while a capture was already running. */
    private var changedDuringCapture = false

    /**
     * Extra delay for change-triggered refreshes after ones that found nothing new, so a
     * stream of events without visible changes can't keep the screen flashing.
     */
    private var backoffMs = 0L

    private var intervalMs = PaperSettings.DEFAULT_INTERVAL_MS
    private var refreshOnChange = true
    private var scheduledAt = NOT_SCHEDULED
    private val refreshRunnable = Runnable {
        scheduledAt = NOT_SCHEDULED
        beginRefresh()
    }

    private val isCapturing: Boolean get() = overlay != null

    // --- Accessibility service lifecycle --------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = PaperSettings(this)
        displayManager = getSystemService(DisplayManager::class.java)
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        geometry = DisplayGeometry.of(display)
        instance = this
        // No consent is needed any more, so pick up where the user left off (e.g. after a reboot).
        if (settings.captureEnabled) startCapture() else notifyState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The config only subscribes to event types that usually mean something visible changed.
        if (isCapturing && refreshOnChange) onContentChanged()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // The user turned the service off; don't restart capture when it's turned back on.
        if (::settings.isInitialized) settings.captureEnabled = false
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        stopCapture()
        if (instance === this) instance = null
        notifyState()
    }

    // --- Capture session ------------------------------------------------------------------

    private fun startCapture(): Boolean {
        if (isCapturing) return true
        geometry = DisplayGeometry.of(display)
        val window = OverlayWindow(this)
        try {
            window.attach(geometry)
        } catch (e: RuntimeException) {
            notifyState()
            return false
        }
        window.setVisible(false)
        overlay = window

        val thread = HandlerThread("PaperScreen-capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        workerThread = thread
        intervalMs = settings.refreshIntervalMs
        refreshOnChange = settings.refreshOnChange
        backoffMs = 0L
        capturer = WindowCapturer(this, Handler(thread.looper), main, this).apply {
            filterParams = settings.filterParams()
        }

        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        displayManager.registerDisplayListener(displayListener, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        settings.captureEnabled = true
        if (isScreenUsable()) resume()
        notifyState()
        return true
    }

    /** Tears the session down; the accessibility service itself stays enabled and idle. */
    private fun stopCapture() {
        val window = overlay ?: return
        phase = Phase.PAUSED
        seq++
        cancelScheduledRefresh()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        displayManager.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        window.detach()
        overlay = null
        capturer?.release()
        capturer = null
        workerThread?.quitSafely()
        workerThread = null
        displayedFrame = null
        notifyState()
    }

    // --- Refresh cycle -------------------------------------------------------------------

    /** Schedules a refresh [delayMs] from now, or else as soon as the interval allows. */
    private fun requestRefresh(delayMs: Long? = null) {
        if (phase != Phase.IDLE) return
        val now = SystemClock.uptimeMillis()
        val at = if (delayMs != null) {
            now + delayMs
        } else {
            max(now, lastRefreshStart + intervalMs + if (refreshOnChange) backoffMs else 0L)
        }
        if (scheduledAt != NOT_SCHEDULED && scheduledAt <= at) return
        main.removeCallbacks(refreshRunnable)
        main.postAtTime(refreshRunnable, at)
        scheduledAt = at
    }

    private fun cancelScheduledRefresh() {
        main.removeCallbacks(refreshRunnable)
        scheduledAt = NOT_SCHEDULED
    }

    private fun beginRefresh() {
        if (phase != Phase.IDLE) return
        val window = overlay ?: return
        val cap = capturer ?: return
        val refresh = ++seq
        phase = Phase.CAPTURING
        changedDuringCapture = false
        lastRefreshStart = SystemClock.uptimeMillis()
        flashStart = lastRefreshStart
        window.view.showFlash()
        cap.capture(refresh, captureTargets(), geometry.width, geometry.height)
    }

    /** Every reported window except accessibility overlays (ours included, if it were listed). */
    private fun captureTargets(): List<WindowCapturer.Target> =
        windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .map { info ->
                val bounds = Rect()
                info.getBoundsInScreen(bounds)
                WindowCapturer.Target(info.id, info.layer, bounds)
            }

    override fun onFrameProcessed(seq: Int, frame: WindowCapturer.Frame?) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        if (frame == null && refreshOnChange) {
            // Nothing visible changed; go straight back to the same frame.
            show(displayedFrame)
            finishRefresh(changed = false)
            return
        }
        val next = frame ?: displayedFrame
        val remaining = max(0L, flashStart + OverlayWindow.FLASH_MS - SystemClock.uptimeMillis())
        main.postDelayed({
            if (seq != this.seq || phase != Phase.CAPTURING) return@postDelayed
            show(next)
            finishRefresh(changed = frame != null)
        }, remaining)
    }

    override fun onCaptureFailed(seq: Int) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        val nothingToShow = displayedFrame == null
        show(displayedFrame) // With no frame yet, this keeps the flash up.
        finishRefresh(changed = false)
        if (nothingToShow) requestRefresh()
    }

    private fun onContentChanged() {
        when (phase) {
            Phase.IDLE -> requestRefresh()
            Phase.CAPTURING -> changedDuringCapture = true
            Phase.PAUSED -> Unit
        }
    }

    private fun show(frame: WindowCapturer.Frame?) {
        displayedFrame = frame
        capturer?.displayed = frame
        overlay?.view?.showFrame(frame?.bitmap)
    }

    private fun finishRefresh(changed: Boolean) {
        phase = Phase.IDLE
        backoffMs = if (changed) 0L else min(max(backoffMs * 2, MIN_BACKOFF_MS), MAX_BACKOFF_MS)
        if (!refreshOnChange || changedDuringCapture) requestRefresh()
    }

    // --- Pause / resume / geometry -------------------------------------------------------

    private fun pause() {
        if (phase == Phase.PAUSED) return
        phase = Phase.PAUSED
        seq++
        cancelScheduledRefresh()
        capturer?.cancel()
        show(null)
        // Accessibility overlays sit above the lock screen, so hide it entirely.
        overlay?.setVisible(false)
    }

    private fun resume() {
        val window = overlay ?: return
        if (phase != Phase.PAUSED) return
        phase = Phase.IDLE
        show(null) // The flash covers the screen until the first capture replaces it.
        window.setVisible(true)
        // Let unlock / dialog-dismiss animations finish before the first capture.
        requestRefresh(RESUME_DELAY_MS)
    }

    private fun onGeometryChanged(newGeometry: DisplayGeometry) {
        geometry = newGeometry
        seq++
        cancelScheduledRefresh()
        capturer?.cancel()
        overlay?.resize(newGeometry)
        show(null) // The old frame no longer lines up with anything; flash until the next one.
        if (phase != Phase.PAUSED) {
            phase = Phase.IDLE
            requestRefresh(ROTATION_DELAY_MS)
        }
    }

    private fun isScreenUsable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON -> if (isScreenUsable()) resume()
                Intent.ACTION_USER_PRESENT -> resume()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display.displayId) return
            val newGeometry = DisplayGeometry.of(display)
            if (newGeometry != geometry) onGeometryChanged(newGeometry)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            PaperSettings.KEY_INTERVAL -> {
                intervalMs = settings.refreshIntervalMs
                if (scheduledAt != NOT_SCHEDULED) {
                    cancelScheduledRefresh()
                    requestRefresh()
                }
            }
            PaperSettings.KEY_WARMTH, PaperSettings.KEY_CONTRAST, PaperSettings.KEY_LEVELS -> {
                capturer?.filterParams = settings.filterParams()
                requestRefresh()
            }
            PaperSettings.KEY_ON_CHANGE -> {
                refreshOnChange = settings.refreshOnChange
                requestRefresh()
            }
        }
    }

    companion object {
        private const val NOT_SCHEDULED = -1L
        private const val RESUME_DELAY_MS = 300L
        private const val ROTATION_DELAY_MS = 450L
        private const val MIN_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 1_000L

        /** The connected service, if the user has enabled it. Main thread only. */
        private var instance: PaperScreenAccessibilityService? = null

        private val stateListeners = mutableSetOf<(State) -> Unit>()

        val state: State
            get() = instance?.let { if (it.isCapturing) State.RUNNING else State.READY } ?: State.DISABLED

        fun addStateListener(listener: (State) -> Unit) {
            stateListeners += listener
        }

        fun removeStateListener(listener: (State) -> Unit) {
            stateListeners -= listener
        }

        private fun notifyState() {
            val current = state
            stateListeners.toList().forEach { it(current) }
        }

        /** Starts filtering the screen. Returns false if the service isn't enabled or connected. */
        fun startCapture(): Boolean = instance?.startCapture() ?: false

        /** Stops filtering; it stays off until the user turns it back on. */
        fun stopCapture() {
            val service = instance ?: return
            service.settings.captureEnabled = false
            service.stopCapture()
        }
    }
}
