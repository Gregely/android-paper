package com.paperscreen

import android.app.Activity
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
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import kotlin.math.max

/**
 * Foreground service that runs the e-ink refresh cycle:
 *
 * 1. clear the overlay so the real screen is visible to the capture,
 * 2. wait for the compositor to settle, then grab one frame,
 * 3. flash the overlay to flat grey (the page turn) while the frame is filtered,
 * 4. show the filtered frame, frozen, until the next refresh.
 *
 * Refreshes happen every interval, or — when "refresh on change" is on — only once the
 * screen has changed and at most once per interval. Capture pauses while the screen is off
 * or locked.
 */
class PaperScreenService : Service(), ScreenCapturer.Listener {

    private enum class Phase { PAUSED, IDLE, CLEARING, GRABBING, FLASHING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var settings: PaperSettings
    private lateinit var displayManager: DisplayManager
    private lateinit var display: Display
    private lateinit var geometry: DisplayGeometry

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

    private var intervalMs = PaperSettings.DEFAULT_INTERVAL_MS
    private var refreshOnChange = true
    private var scheduledAt = NOT_SCHEDULED
    private var watchIgnoreMs = 0L
    private val refreshRunnable = Runnable {
        scheduledAt = NOT_SCHEDULED
        beginRefresh()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = PaperSettings(this)
        workerThread = HandlerThread("PaperScreen-capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        worker = Handler(workerThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        geometry = DisplayGeometry.of(display)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() must be answered with startForeground(), and the
        // mediaProjection type must be active before the projection is created.
        startInForeground()
        if (projection != null) return START_NOT_STICKY // Already running.

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = resultData(intent)

        val mediaProjection = if (data != null && resultCode == Activity.RESULT_OK) {
            try {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
            } catch (e: SecurityException) {
                null
            }
        } else {
            null
        }
        if (mediaProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mediaProjection
        // Required before createVirtualDisplay on Android 14.
        mediaProjection.registerCallback(projectionCallback, main)

        if (!startPipeline(mediaProjection)) {
            stopSelf()
            return START_NOT_STICKY
        }
        setRunning(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setRunning(false)
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
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun startPipeline(mediaProjection: MediaProjection): Boolean {
        geometry = DisplayGeometry.of(display)
        val window = OverlayWindow(this, display)
        try {
            window.attach(geometry)
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.overlay_failed, Toast.LENGTH_LONG).show()
            return false
        }
        window.setVisible(false)
        overlay = window

        intervalMs = settings.refreshIntervalMs
        refreshOnChange = settings.refreshOnChange
        capturer = ScreenCapturer(mediaProjection, worker, main, this).apply {
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
        phase = Phase.CLEARING
        lastRefreshStart = SystemClock.uptimeMillis()

        window.view.showClear()
        cap.prepareGrab()
        main.postDelayed({
            if (seq != refresh || phase != Phase.CLEARING) return@postDelayed
            phase = Phase.GRABBING
            // Redraw (still transparent) so a frame is composited even if nothing else moves.
            window.view.nudge()
            cap.grab(refresh, GRAB_TIMEOUT_MS)
        }, geometry.settleMs)
    }

    override fun onFrameCaptured(seq: Int) {
        if (seq != this.seq || phase != Phase.GRABBING) return
        phase = Phase.FLASHING
        flashStart = SystemClock.uptimeMillis()
        overlay?.view?.showFlash()
    }

    override fun onFrameProcessed(seq: Int, frame: ScreenCapturer.Frame?) {
        if (seq != this.seq || phase != Phase.FLASHING) return
        if (frame == null && refreshOnChange) {
            // Nothing visible changed; skip the rest of the page turn.
            show(displayedFrame)
            finishRefresh(changed = false)
            return
        }
        val next = frame ?: displayedFrame
        val remaining = max(0L, flashStart + OverlayWindow.FLASH_MS - SystemClock.uptimeMillis())
        main.postDelayed({
            if (seq != this.seq || phase != Phase.FLASHING) return@postDelayed
            show(next)
            finishRefresh(changed = frame != null)
        }, remaining)
    }

    override fun onFrameBlank(seq: Int) {
        if (seq != this.seq || phase != Phase.GRABBING) return
        // Leave the (secure) content visible rather than freezing a black screen over it.
        val wasShowingFrame = displayedFrame != null
        show(null)
        finishRefresh(changed = wasShowingFrame)
    }

    override fun onCaptureTimedOut(seq: Int) {
        if (seq != this.seq || (phase != Phase.GRABBING && phase != Phase.FLASHING)) return
        show(displayedFrame)
        finishRefresh(changed = false)
    }

    override fun onContentChanged() {
        if (refreshOnChange) requestRefresh()
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
        overlay?.setVisible(false)
        updateNotification()
    }

    private fun resume() {
        if (phase != Phase.PAUSED || overlay == null) return
        phase = Phase.IDLE
        show(null)
        overlay?.setVisible(true)
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
            stopSelf()
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
        private const val ACTION_START = "com.paperscreen.action.START"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "paperscreen"
        private const val NOTIFICATION_ID = 1

        private const val NOT_SCHEDULED = -1L
        private const val GRAB_TIMEOUT_MS = 400L
        private const val RESUME_DELAY_MS = 300L
        private const val ROTATION_DELAY_MS = 450L
        private const val WATCH_MARGIN_MS = 100L
        private const val MAX_WATCH_IGNORE_MS = 1_000L

        /** Whether the service is currently filtering the screen. Main thread only. */
        var isRunning = false
            private set

        private val stateListeners = mutableSetOf<(Boolean) -> Unit>()

        fun addStateListener(listener: (Boolean) -> Unit) {
            stateListeners += listener
        }

        fun removeStateListener(listener: (Boolean) -> Unit) {
            stateListeners -= listener
        }

        /** Always notifies, so a start that failed before running still resets the UI. */
        private fun setRunning(running: Boolean) {
            isRunning = running
            stateListeners.toList().forEach { it(running) }
        }

        /** Starts the service with a screen-capture consent result from MediaProjectionManager. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, PaperScreenService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PaperScreenService::class.java))
        }

        private fun resultData(intent: Intent): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
    }
}
