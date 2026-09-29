package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the whole pipeline: screen capture, filtering, and the full-screen overlay.
 *
 * It is an AccessibilityService only so its overlay can use TYPE_ACCESSIBILITY_OVERLAY, which
 * may be fully opaque while still passing touches through. It reads no accessibility events
 * and no window content. Enabling the service in system settings just makes it ready;
 * capture runs between [startCapture] (after the user grants screen-capture consent in
 * [SettingsActivity]) and [stopCapture], as a mediaProjection foreground service.
 *
 * Each refresh is one page turn:
 *
 * 1. the overlay flashes to flat grey, hiding the old frame,
 * 2. near the end of the flash it goes clear for a single frame, and that frame is captured
 *    (the grey is back on the next vsync),
 * 3. the capture is filtered on a worker thread and replaces the flash.
 *
 * Refreshes happen every interval, or — with "refresh on change" — only once the screen has
 * changed, at most once per interval. Capture pauses while the screen is off or locked.
 */
class PaperScreenAccessibilityService : AccessibilityService(), ScreenCapturer.Listener {

    /** What the settings screen can do with the service. */
    enum class State { DISABLED, READY, RUNNING }

    private enum class Phase { PAUSED, IDLE, FLASHING, CAPTURING, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: PaperSettings
    private lateinit var displayManager: DisplayManager
    private lateinit var display: Display
    private lateinit var geometry: DisplayGeometry

    // One capture session, from startCapture to stopCapture.
    private var workerThread: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var overlay: OverlayWindow? = null
    private var listenersRegistered = false

    private var phase = Phase.PAUSED
    /** Incremented whenever an in-flight refresh must be abandoned. */
    private var seq = 0
    private var lastRefreshStart = 0L
    private var flashStart = 0L
    private var displayedFrame: ScreenCapturer.Frame? = null

    /**
     * The last capture was blanked by FLAG_SECURE content, so the overlay is clear and the
     * secure app shows through unfiltered. There's nothing to hide, so the next refresh
     * captures without a flash first.
     */
    private var passthrough = false

    /**
     * How many frames the overlay goes clear to be captured. One is enough unless the
     * compositor drops the clear frame (a missed capture); then this steps up, and back down
     * after a run of clean captures.
     */
    private var clearFrames = 1
    private var cleanCaptures = 0

    private var intervalMs = PaperSettings.DEFAULT_INTERVAL_MS
    private var refreshOnChange = true
    private var scheduledAt = NOT_SCHEDULED
    private var watchIgnoreMs = 0L
    private val refreshRunnable = Runnable {
        scheduledAt = NOT_SCHEDULED
        beginRefresh()
    }

    private val isCapturing: Boolean get() = projection != null

    // --- Accessibility service lifecycle --------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = PaperSettings(this)
        displayManager = getSystemService(DisplayManager::class.java)
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        geometry = DisplayGeometry.of(display)
        createNotificationChannel()
        instance = this
        notifyState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
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

    /** Starts filtering the screen with a consent result from MediaProjectionManager. */
    private fun startCapture(resultCode: Int, data: Intent): Boolean {
        if (isCapturing) return true
        try {
            // The mediaProjection foreground type must be active before the projection exists.
            startInForeground()
        } catch (e: RuntimeException) {
            return false
        }
        val mediaProjection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            null
        }
        if (mediaProjection == null) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            return false
        }
        projection = mediaProjection
        // Required before createVirtualDisplay on Android 14.
        mediaProjection.registerCallback(projectionCallback, main)
        if (!startPipeline(mediaProjection)) {
            stopCapture()
            return false
        }
        notifyState()
        return true
    }

    private fun startPipeline(mediaProjection: MediaProjection): Boolean {
        geometry = DisplayGeometry.of(display)
        val window = OverlayWindow(this)
        try {
            window.attach(geometry)
        } catch (e: RuntimeException) {
            return false
        }
        window.setVisible(false)
        overlay = window

        val thread = HandlerThread("PaperScreen-capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        workerThread = thread
        intervalMs = settings.refreshIntervalMs
        refreshOnChange = settings.refreshOnChange
        clearFrames = 1
        cleanCaptures = 0
        capturer = ScreenCapturer(mediaProjection, Handler(thread.looper), main, this).apply {
            filterParams = settings.filterParams()
            start(geometry)
        }

        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        displayManager.registerDisplayListener(displayListener, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        listenersRegistered = true

        if (isScreenUsable()) resume() else updateNotification()
        return true
    }

    /** Tears the session down; the accessibility service itself stays enabled and idle. */
    private fun stopCapture() {
        if (projection == null && overlay == null && capturer == null) return
        phase = Phase.PAUSED
        seq++
        cancelScheduledRefresh()
        if (listenersRegistered) {
            listenersRegistered = false
            settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            displayManager.unregisterDisplayListener(displayListener)
            unregisterReceiver(screenReceiver)
        }
        overlay?.detach()
        overlay = null
        capturer?.release()
        capturer = null
        workerThread?.quitSafely()
        workerThread = null
        displayedFrame = null
        passthrough = false
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        notifyState()
    }

    // --- Refresh cycle -------------------------------------------------------------------

    /** Schedules a refresh [delayMs] from now, or else as soon as the interval allows. */
    private fun requestRefresh(delayMs: Long? = null) {
        if (phase != Phase.IDLE) return
        val now = SystemClock.uptimeMillis()
        val at = if (delayMs != null) now + delayMs else max(now, lastRefreshStart + intervalMs)
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
        lastRefreshStart = SystemClock.uptimeMillis()

        if (passthrough) {
            // The overlay is already clear, so capture right away; the flash follows.
            phase = Phase.CAPTURING
            cap.grab(refresh, GRAB_TIMEOUT_MS)
            window.view.nudge()
            return
        }

        phase = Phase.FLASHING
        flashStart = lastRefreshStart
        window.view.showFlash()
        val g = geometry
        // Once the flash is on screen, start watching composited frames for a reference.
        main.postDelayed({
            if (seq == refresh && phase == Phase.FLASHING) cap.prepareGrab()
        }, g.settleMs)
        // Near the end of the flash, go clear for a single frame and capture it.
        main.postDelayed({
            if (seq != refresh || phase != Phase.FLASHING) return@postDelayed
            phase = Phase.CAPTURING
            cap.grab(refresh, GRAB_TIMEOUT_MS)
            window.view.blinkClear(clearFrames)
        }, max(g.settleMs + g.frameMs, OverlayWindow.FLASH_MS - 3 * g.frameMs))
    }

    override fun onFrameCaptured(seq: Int) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        phase = Phase.PROCESSING
        if (passthrough) {
            // Coming out of passthrough: flash now, over the live screen.
            flashStart = SystemClock.uptimeMillis()
            overlay?.view?.showFlash()
        }
    }

    override fun onFrameProcessed(seq: Int, frame: ScreenCapturer.Frame?) {
        if (seq != this.seq || phase != Phase.PROCESSING) return
        passthrough = false
        noteCleanCapture()
        if (frame == null && refreshOnChange) {
            // Nothing visible changed; go straight back to the same frame.
            show(displayedFrame)
            finishRefresh(changed = false)
            return
        }
        val next = frame ?: displayedFrame
        val remaining = max(0L, flashStart + OverlayWindow.FLASH_MS - SystemClock.uptimeMillis())
        main.postDelayed({
            if (seq != this.seq || phase != Phase.PROCESSING) return@postDelayed
            show(next)
            finishRefresh(changed = frame != null)
        }, remaining)
    }

    override fun onFrameBlank(seq: Int) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        noteCleanCapture()
        // Leave the (secure) content visible rather than freezing a black screen over it.
        val wasShowingFrame = displayedFrame != null
        passthrough = true
        show(null)
        finishRefresh(changed = wasShowingFrame)
    }

    override fun onCaptureTimedOut(seq: Int) {
        if (seq != this.seq || (phase != Phase.CAPTURING && phase != Phase.PROCESSING)) return
        if (!passthrough) {
            // The clear frame probably never reached the compositor; stay clear longer next time.
            clearFrames = min(clearFrames + 1, MAX_CLEAR_FRAMES)
            cleanCaptures = 0
        }
        val nothingToShow = displayedFrame == null && !passthrough
        if (nothingToShow) {
            overlay?.view?.showFlash() // Keep the live screen covered.
        } else {
            show(displayedFrame)
        }
        finishRefresh(changed = false)
        if (nothingToShow) requestRefresh()
    }

    override fun onContentChanged() {
        if (refreshOnChange) requestRefresh()
    }

    private fun noteCleanCapture() {
        if (clearFrames > 1 && ++cleanCaptures >= CLEAN_CAPTURES_TO_STEP_DOWN) {
            clearFrames--
            cleanCaptures = 0
        }
    }

    private fun show(frame: ScreenCapturer.Frame?) {
        displayedFrame = frame
        capturer?.displayed = frame
        overlay?.view?.showFrame(frame?.bitmap)
    }

    private fun finishRefresh(changed: Boolean) {
        phase = Phase.IDLE
        if (!refreshOnChange) {
            requestRefresh()
            return
        }
        // Our own redraw is composited (and seen by the watcher) shortly after it's recorded,
        // so start the ignore window from the draw. If refreshes keep finding nothing new,
        // something is re-triggering them; back off so a loop can't strobe the screen.
        watchIgnoreMs = if (changed) {
            baseWatchIgnoreMs()
        } else {
            (watchIgnoreMs * 2).coerceIn(baseWatchIgnoreMs(), MAX_WATCH_IGNORE_MS)
        }
        val refresh = seq
        overlay?.view?.doAfterNextDraw {
            if (seq == refresh && phase == Phase.IDLE && refreshOnChange) capturer?.watch(watchIgnoreMs)
        }
    }

    private fun baseWatchIgnoreMs() = geometry.settleMs + WATCH_MARGIN_MS

    // --- Pause / resume / geometry -------------------------------------------------------

    private fun pause() {
        if (phase == Phase.PAUSED) return
        phase = Phase.PAUSED
        seq++
        cancelScheduledRefresh()
        capturer?.idle()
        overlay?.view?.cancelAfterDraw()
        show(null)
        passthrough = false
        // Accessibility overlays sit above the lock screen, so hide it entirely.
        overlay?.setVisible(false)
        updateNotification()
    }

    private fun resume() {
        val window = overlay ?: return
        if (phase != Phase.PAUSED) return
        phase = Phase.IDLE
        show(null)
        // Cover the live screen straight away; the first capture replaces the flash.
        window.view.showFlash()
        window.setVisible(true)
        // Let unlock / dialog-dismiss animations finish before the first capture.
        requestRefresh(RESUME_DELAY_MS)
        updateNotification()
    }

    private fun onGeometryChanged(newGeometry: DisplayGeometry) {
        val old = geometry
        geometry = newGeometry
        if (old.width == newGeometry.width && old.height == newGeometry.height &&
            old.rotation == newGeometry.rotation && old.densityDpi == newGeometry.densityDpi
        ) {
            return // Only the refresh rate changed.
        }
        seq++
        cancelScheduledRefresh()
        val cap = capturer ?: return
        cap.idle()
        cap.resize(newGeometry)
        overlay?.resize(newGeometry)
        show(null) // The old frame no longer lines up with anything.
        if (phase != Phase.PAUSED) {
            phase = Phase.IDLE
            passthrough = false
            overlay?.view?.showFlash()
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
                updateNotification()
            }
            PaperSettings.KEY_WARMTH, PaperSettings.KEY_CONTRAST, PaperSettings.KEY_LEVELS -> {
                capturer?.filterParams = settings.filterParams()
                requestRefresh()
            }
            PaperSettings.KEY_ON_CHANGE -> {
                refreshOnChange = settings.refreshOnChange
                if (phase == Phase.IDLE) {
                    capturer?.idle()
                    requestRefresh()
                }
                updateNotification()
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        // The user revoked capture from system UI, or the system ended it (e.g. on lock).
        override fun onStop() {
            stopCapture()
        }
    }

    // --- Notification --------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (projection == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            projection != null && phase == Phase.PAUSED -> getString(R.string.notification_paused)
            refreshOnChange -> getString(R.string.notification_text_on_change, intervalMs)
            else -> getString(R.string.notification_text, intervalMs)
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(R.string.action_stop),
            stop,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(stopAction)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "paperscreen"
        private const val NOTIFICATION_ID = 1

        private const val NOT_SCHEDULED = -1L
        private const val GRAB_TIMEOUT_MS = 250L
        private const val RESUME_DELAY_MS = 300L
        private const val ROTATION_DELAY_MS = 450L
        private const val WATCH_MARGIN_MS = 100L
        private const val MAX_WATCH_IGNORE_MS = 1_000L
        private const val MAX_CLEAR_FRAMES = 3
        private const val CLEAN_CAPTURES_TO_STEP_DOWN = 20

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

        /**
         * Starts capture with a consent result from MediaProjectionManager. Returns false if
         * the service isn't enabled or capture couldn't start.
         */
        fun startCapture(resultCode: Int, data: Intent): Boolean =
            instance?.startCapture(resultCode, data) ?: false

        fun stopCapture() {
            instance?.stopCapture()
        }
    }
}
