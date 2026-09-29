package com.paperscreen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the whole pipeline: per-window capture, filtering, and the full-screen overlay.
 *
 * The overlay is a TYPE_ACCESSIBILITY_OVERLAY window, so it can be fully opaque while
 * touches pass through. The screen underneath is rebuilt from per-window screenshots
 * ([WindowCapturer]), which never include the overlay.
 *
 * Refreshes work like an e-ink panel:
 *
 * - **Partial refresh** (usual): the overlay keeps showing the old frame while the new one is
 *   captured and filtered in the background; then only the pixels that changed are written
 *   into it. No flash; static areas never change.
 * - **Full refresh** (every N partial refreshes, and for the first frame): the new frame is
 *   shown with inverted colours for the flash duration (~350 ms), then normally — e-ink's
 *   ghosting-clear cycle. Can be turned off, making every refresh partial.
 *
 * Refreshes happen every interval, or — with "refresh on change" — when accessibility events
 * report that something on screen changed, at most once per interval. Capture pauses while
 * the screen is off or locked, and can be paused for five minutes from the notification.
 */
class PaperScreenAccessibilityService : AccessibilityService(), WindowCapturer.Listener {

    /** What the settings screen can do with the service. */
    enum class State { DISABLED, READY, RUNNING, SNOOZED }

    private enum class Phase { PAUSED, IDLE, CAPTURING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: PaperSettings
    private lateinit var displayManager: DisplayManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var display: Display
    private lateinit var geometry: DisplayGeometry

    // One capture session, from startCapture to stopCapture.
    private var workerThread: HandlerThread? = null
    private var capturer: WindowCapturer? = null
    private var overlay: OverlayWindow? = null
    private var securePill: SecurePill? = null

    private var phase = Phase.PAUSED
    /** Incremented whenever an in-flight refresh must be abandoned. */
    private var seq = 0
    private var lastRefreshStart = 0L

    /**
     * The frame on screen, updated in place by partial refreshes. It and the capturer's copy
     * are cleared together; [frameEpoch] tags which clearing an update was computed after.
     */
    private var frameBitmap: Bitmap? = null
    private var frameEpoch = 0
    private var partialsSinceFull = 0

    /** Something changed while a capture was already running. */
    private var changedDuringCapture = false

    /**
     * Extra delay for change-triggered refreshes after ones that found nothing new, so a
     * stream of events without visible changes can't keep capturing.
     */
    private var backoffMs = 0L

    /** A secure window was seen in the last capture (so the message isn't repeated). */
    private var secureNoticeShown = false

    /**
     * Just after the screen turns on or unlocks, the lock screen and its transition windows
     * are FLAG_SECURE, so captures look like a secure app. Until this time (uptime), captures
     * that hit a secure window are dropped and the secure-app pill isn't shown.
     */
    private var graceUntil = 0L
    private var captureInGrace = false

    /** Paused because the screen went off: the frame is kept, so unlocking is a partial refresh. */
    private var pausedForScreenOff = false

    /** The filter settings last handed to the capturer (warmth changes with Night warmth). */
    private var appliedParams: EinkFilter.Params? = null
    private val warmthTick = Runnable {
        applyFilterParams()
        scheduleWarmthTick()
    }

    /** When a "pause for 5 minutes" ends, in [SystemClock.elapsedRealtime]; 0 if not paused. */
    private var snoozedUntil = 0L
    private val endSnoozeRunnable = Runnable { endSnooze() }

    private var intervalMs = RefreshPreset.DEFAULT.ms ?: 0
    private var refreshOnChange = true
    private var fullRefresh = true
    private var fullRefreshEvery = PaperSettings.DEFAULT_FULL_REFRESH_EVERY
    private var flashMs = PaperSettings.DEFAULT_FLASH_MS.toLong()
    private var ghosting = true
    private var scheduledAt = NOT_SCHEDULED
    private val refreshRunnable = Runnable {
        scheduledAt = NOT_SCHEDULED
        beginRefresh()
    }

    /** Filtering is on (the overlay itself only exists while not paused). */
    private var capturing = false
    private val isCapturing: Boolean get() = capturing
    private val isSnoozed: Boolean get() = snoozedUntil != 0L

    // --- Accessibility service lifecycle --------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = PaperSettings(this)
        displayManager = getSystemService(DisplayManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        geometry = DisplayGeometry.of(display)
        createNotificationChannel()
        instance = this
        // Pick up where the user left off (e.g. after a reboot).
        if (settings.captureEnabled) startCapture() else notifyState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Unlocking produces window events too, so this doubles as a safety net for resuming.
        resumeIfDue()
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
        val thread = HandlerThread("PaperScreen-capture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        workerThread = thread
        intervalMs = settings.refreshIntervalMs
        refreshOnChange = settings.refreshOnChange
        readRefreshStyle()
        backoffMs = 0L
        capturer = WindowCapturer(this, Handler(thread.looper), main, this)
        appliedParams = null
        applyFilterParams()
        scheduleWarmthTick()

        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        displayManager.registerDisplayListener(displayListener, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        capturing = true
        phase = Phase.PAUSED
        restoreSnooze()
        if (isScreenUsable() && !resume()) {
            stopCapture()
            return false
        }
        settings.captureEnabled = true
        updateNotification()
        notifyState()
        return true
    }

    /** Tears the session down; the accessibility service itself stays enabled and idle. */
    private fun stopCapture() {
        if (!capturing) return
        capturing = false
        phase = Phase.PAUSED
        seq++
        cancelScheduledRefresh()
        clearSnooze()
        main.removeCallbacks(warmthTick)
        appliedParams = null
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        displayManager.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        hideOverlay()
        capturer?.release()
        capturer = null
        workerThread?.quitSafely()
        workerThread = null
        frameBitmap = null
        notificationManager.cancel(NOTIFICATION_ID)
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

    /** The overlay keeps showing the current frame while the next one is captured. */
    private fun beginRefresh() {
        if (phase != Phase.IDLE) return
        val cap = capturer ?: return
        val refresh = ++seq
        phase = Phase.CAPTURING
        changedDuringCapture = false
        lastRefreshStart = SystemClock.uptimeMillis()
        captureInGrace = lastRefreshStart < graceUntil
        cap.capture(refresh, captureTargets(), geometry.width, geometry.height, rejectSecure = captureInGrace)
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

    override fun onFrameProcessed(seq: Int, update: WindowCapturer.Update?, secureWindow: Boolean) {
        val current = seq == this.seq && phase == Phase.CAPTURING
        // Just after unlocking, no forced full refresh: the first good frame is a partial one,
        // and a due full refresh waits for the next capture after the grace period.
        val fullDue = fullRefresh && !captureInGrace && partialsSinceFull >= fullRefreshEvery
        // The capturer has already recorded this frame as the one on screen, so it's written
        // into the frame even when the refresh itself was abandoned (e.g. the screen went off
        // mid-capture); otherwise the next diff would be taken against pixels never shown.
        val applied = update?.takeIf { it.epoch == frameEpoch }?.let {
            applyUpdate(it, withGhosts = current && ghosting && !fullDue)
        }
        if (!current) {
            if (applied != null) overlay?.view?.frameChanged()
            return
        }
        if (!captureInGrace) noteSecureWindow(secureWindow)
        val view = overlay?.view
        if (view == null || applied == null) {
            finishRefresh(changed = false)
            return
        }
        // With full refreshes off, even the first frame just replaces the blank page.
        val full = fullDue || (fullRefresh && applied.firstFrame && !captureInGrace)
        logUpdate(update, full)

        if (full) {
            // Full refresh: flash the new frame inverted, then show it.
            partialsSinceFull = 0
            view.setInverted(true)
            main.postDelayed({
                if (seq != this.seq || phase != Phase.CAPTURING) return@postDelayed
                view.setInverted(false)
                finishRefresh(changed = true)
            }, flashMs)
        } else {
            // Partial refresh: only the changed pixels were written (ghosting, if on).
            partialsSinceFull++
            view.frameChanged(applied.ghosts)
            finishRefresh(changed = true)
        }
    }

    private class Applied(val firstFrame: Boolean, val ghosts: List<OverlayWindow.FrameView.Ghost>)

    /** Writes [update] into the frame bitmap (creating it for a first frame). */
    private fun applyUpdate(update: WindowCapturer.Update, withGhosts: Boolean): Applied {
        var bitmap = frameBitmap
        val firstFrame = bitmap == null || bitmap.width != update.width || bitmap.height != update.height
        if (bitmap == null || firstFrame) {
            bitmap = Bitmap.createBitmap(update.width, update.height, Bitmap.Config.ARGB_8888)
                .apply { setHasAlpha(false) }
            frameBitmap = bitmap
            overlay?.view?.setFrame(bitmap)
        }
        // Keep what the changed regions showed before, for the partial-refresh ghost trail.
        val ghosts = if (firstFrame || !withGhosts) emptyList() else update.bands.map { band ->
            OverlayWindow.FrameView.Ghost(
                Bitmap.createBitmap(bitmap, band.left, band.top, band.width, band.height),
                band.left,
                band.top,
            )
        }
        update.applyTo(bitmap)
        return Applied(firstFrame, ghosts)
    }

    /** `adb logcat -s PaperScreen` shows how much of the screen each refresh rewrote. */
    private fun logUpdate(update: WindowCapturer.Update, full: Boolean) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val changed = update.bands.sumOf { it.width.toLong() * it.height }
        val percent = 100.0 * changed / (update.width.toLong() * update.height)
        Log.d(
            TAG,
            "%s refresh: %d band(s), %.1f%% of the screen rewritten".format(
                if (full) "full" else "partial",
                update.bands.size,
                percent,
            ),
        )
    }

    /** Just unlocked and the lock screen was still in the capture: try again shortly. */
    override fun onCaptureRejected(seq: Int) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        phase = Phase.IDLE
        requestRefresh(GRACE_RETRY_MS)
    }

    override fun onCaptureFailed(seq: Int) {
        if (seq != this.seq || phase != Phase.CAPTURING) return
        finishRefresh(changed = false)
        if (frameBitmap == null) requestRefresh() // Still on the blank page; keep trying.
    }

    private fun onContentChanged() {
        when (phase) {
            Phase.IDLE -> requestRefresh()
            Phase.CAPTURING -> changedDuringCapture = true
            Phase.PAUSED -> Unit
        }
    }

    private fun finishRefresh(changed: Boolean) {
        phase = Phase.IDLE
        backoffMs = if (changed) 0L else min(max(backoffMs * 2, MIN_BACKOFF_MS), MAX_BACKOFF_MS)
        if (!refreshOnChange || changedDuringCapture) requestRefresh()
    }

    /**
     * Once per secure-app visit, explains the black box with a pill that pauses filtering
     * (exactly like the notification's Pause button) when tapped.
     */
    private fun noteSecureWindow(present: Boolean) {
        if (present && !secureNoticeShown && overlay != null) {
            val pill = securePill ?: SecurePill(this) { snooze() }.also { securePill = it }
            pill.show(settings.effectiveWarmth(), SECURE_MESSAGE_MS)
        }
        secureNoticeShown = present
    }

    /**
     * Drops the frame on screen (it no longer matches anything) and abandons any refresh in
     * flight. The overlay shows a blank page until the next frame, which is a full refresh.
     */
    private fun clearFrame() {
        seq++
        frameEpoch++
        cancelScheduledRefresh()
        capturer?.reset(frameEpoch)
        frameBitmap = null
        overlay?.view?.setFrame(null)
    }

    // --- Pause / resume / snooze / geometry ------------------------------------------------

    /**
     * Stops refreshing and removes the overlay (accessibility overlays sit above the lock
     * screen); resume() creates a fresh window rather than trusting a hidden one to come back.
     * For the screen going off, [keepFrame] keeps the last frame so unlocking shows it and
     * refreshes only what changed; otherwise (a pause) it's dropped for a clean full refresh.
     */
    private fun pause(keepFrame: Boolean = false) {
        if (phase == Phase.PAUSED) return
        phase = Phase.PAUSED
        pausedForScreenOff = keepFrame
        if (keepFrame) {
            seq++
            cancelScheduledRefresh()
        } else {
            clearFrame()
        }
        hideOverlay()
        Log.i(TAG, "paused")
    }

    /** Shows a fresh overlay (a blank page) and schedules the first capture. */
    private fun resume(): Boolean {
        if (!capturing || phase != Phase.PAUSED || isSnoozed) return true
        geometry = DisplayGeometry.of(display)
        if (!showOverlay()) return false
        phase = Phase.IDLE
        secureNoticeShown = false
        // Coming back from the screen being off: the lock screen may still be in the first
        // captures (in case the screen-on/unlock broadcasts were late or missed).
        if (pausedForScreenOff) startGrace()
        pausedForScreenOff = false
        // The kept frame (or a blank page) shows until the first capture; let unlock
        // animations finish first.
        requestRefresh(RESUME_DELAY_MS)
        Log.i(TAG, "resumed")
        return true
    }

    /**
     * Resumes if filtering should be showing but isn't: screen on, unlocked, not snoozed.
     * Called from every signal that can follow an unlock, not just ACTION_USER_PRESENT.
     */
    private fun resumeIfDue() {
        if (!capturing) return
        if (snoozeExpired()) {
            endSnooze()
        } else if (phase == Phase.PAUSED && !isSnoozed && isScreenUsable()) {
            resume()
        }
    }

    private fun showOverlay(): Boolean {
        hideOverlay()
        val window = OverlayWindow(this)
        try {
            window.attach(geometry)
        } catch (e: RuntimeException) {
            Log.w(TAG, "couldn't add the overlay", e)
            return false
        }
        window.view.blankColor = EinkFilter.paperColor(settings.filterParams())
        window.view.setFrame(frameBitmap)
        overlay = window
        applyGhostStyle()
        return true
    }

    /**
     * Starts the post-unlock grace period, from whichever of screen-on and unlock comes first.
     * If it has already run out by the time the phone unlocks (say, while typing a PIN), the
     * unlock starts it again, since the unlock animation is what it's there to cover.
     */
    private fun startGrace() {
        val now = SystemClock.uptimeMillis()
        if (now < graceUntil) return
        graceUntil = now + GRACE_MS
    }

    /** Hands the current filter settings (warmth may follow Night warmth) to the capturer. */
    private fun applyFilterParams() {
        val params = settings.filterParams()
        if (params == appliedParams) return
        appliedParams = params
        capturer?.filterParams = params
        overlay?.view?.blankColor = EinkFilter.paperColor(params)
        requestRefresh()
    }

    /** While Night warmth is on, re-checks the warmth just after each minute boundary. */
    private fun scheduleWarmthTick() {
        main.removeCallbacks(warmthTick)
        if (capturing && settings.nightWarmth) {
            main.postDelayed(warmthTick, 60_000 - System.currentTimeMillis() % 60_000 + 500)
        }
    }

    private fun readRefreshStyle() {
        fullRefresh = settings.fullRefresh
        fullRefreshEvery = settings.fullRefreshEvery
        flashMs = settings.flashDurationMs.toLong()
        ghosting = settings.ghosting
        applyGhostStyle()
    }

    private fun applyGhostStyle() {
        val view = overlay?.view ?: return
        view.ghostAlpha = settings.ghostOpacity / 100f
        view.ghostFadeMs = settings.ghostFadeMs.toLong()
    }

    private fun hideOverlay() {
        securePill?.hide()
        overlay?.detach()
        overlay = null
    }

    /**
     * Pauses filtering for five minutes. Idempotent: pausing again while paused (from the
     * notification and the secure-app pill, say) doesn't extend the pause.
     */
    private fun snooze() {
        if (!isCapturing || isSnoozed) return
        settings.snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
        startSnooze(SNOOZE_MS)
    }

    /** Picks up a pause saved before the process was restarted, if it hasn't run out. */
    private fun restoreSnooze() {
        val remaining = settings.snoozeUntil - System.currentTimeMillis()
        if (remaining > 0) startSnooze(remaining) else settings.snoozeUntil = 0L
    }

    private fun startSnooze(durationMs: Long) {
        snoozedUntil = SystemClock.elapsedRealtime() + durationMs
        main.removeCallbacks(endSnoozeRunnable)
        main.postDelayed(endSnoozeRunnable, durationMs)
        pause()
        updateNotification()
        notifyState()
    }

    private fun endSnooze() {
        if (!isSnoozed) return
        clearSnooze()
        if (isScreenUsable()) resume()
        updateNotification()
        notifyState()
    }

    private fun clearSnooze() {
        snoozedUntil = 0L
        settings.snoozeUntil = 0L
        main.removeCallbacks(endSnoozeRunnable)
    }

    /** The snooze timer doesn't run in deep sleep; catch up when the screen comes back. */
    private fun snoozeExpired() = isSnoozed && SystemClock.elapsedRealtime() >= snoozedUntil

    private fun onGeometryChanged(newGeometry: DisplayGeometry) {
        geometry = newGeometry
        overlay?.resize(newGeometry)
        // The old frame no longer lines up with anything, including one kept while the
        // screen was off.
        clearFrame()
        if (phase == Phase.PAUSED) return
        phase = Phase.IDLE
        requestRefresh(ROTATION_DELAY_MS)
    }

    private fun isScreenUsable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> pause(keepFrame = true)
                // The keyguard can still report itself locked for a moment after USER_PRESENT.
                Intent.ACTION_USER_PRESENT -> {
                    startGrace()
                    if (snoozeExpired()) endSnooze() else resume()
                }
                Intent.ACTION_SCREEN_ON -> {
                    startGrace()
                    resumeIfDue()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display.displayId) return
            // Also fires when the display turns on, sometimes before the broadcast arrives.
            if (phase == Phase.PAUSED && display.state == Display.STATE_ON) startGrace()
            resumeIfDue()
            val newGeometry = DisplayGeometry.of(display)
            if (newGeometry != geometry) onGeometryChanged(newGeometry)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            PaperSettings.KEY_PRESET, PaperSettings.KEY_CUSTOM_INTERVAL -> {
                intervalMs = settings.refreshIntervalMs
                if (scheduledAt != NOT_SCHEDULED) {
                    cancelScheduledRefresh()
                    requestRefresh()
                }
            }
            PaperSettings.KEY_WARMTH, PaperSettings.KEY_CONTRAST, PaperSettings.KEY_LEVELS,
            PaperSettings.KEY_POSTERIZE, PaperSettings.KEY_NIGHT_SCHEDULE, PaperSettings.KEY_NIGHT_START,
            PaperSettings.KEY_NIGHT_END, PaperSettings.KEY_DAY_WARMTH, PaperSettings.KEY_NIGHT_WARMTH_LEVEL,
            PaperSettings.KEY_NIGHT_TRANSITION,
            -> applyFilterParams()
            PaperSettings.KEY_NIGHT_WARMTH -> {
                applyFilterParams()
                scheduleWarmthTick()
            }
            PaperSettings.KEY_ON_CHANGE -> {
                refreshOnChange = settings.refreshOnChange
                requestRefresh()
            }
            PaperSettings.KEY_FULL_REFRESH, PaperSettings.KEY_FULL_REFRESH_EVERY, PaperSettings.KEY_FLASH_DURATION,
            PaperSettings.KEY_GHOSTING, PaperSettings.KEY_GHOST_OPACITY, PaperSettings.KEY_GHOST_FADE,
            -> readRefreshStyle()
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
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * A regular (not foreground-service) notification while filtering is on. Posting is a
     * no-op if the user declined notifications.
     */
    private fun updateNotification() {
        if (!isCapturing) return
        val text = if (isSnoozed) {
            val wallClockEnd = System.currentTimeMillis() + (snoozedUntil - SystemClock.elapsedRealtime())
            getString(R.string.notification_snoozed, DateFormat.getTimeFormat(this).format(Date(wallClockEnd)))
        } else {
            getString(R.string.notification_text)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        if (isSnoozed) {
            builder.addAction(action(ACTION_RESUME, R.string.action_resume))
        } else {
            builder.addAction(action(ACTION_SNOOZE, R.string.action_snooze))
        }
        builder.addAction(action(ACTION_STOP, R.string.action_stop))
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun action(name: String, label: Int): Notification.Action {
        val intent = Intent(this, NotificationActionReceiver::class.java).setAction(name)
        val pending = PendingIntent.getBroadcast(this, name.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(label),
            pending,
        ).build()
    }

    companion object {
        private const val TAG = "PaperScreen"
        private const val NOT_SCHEDULED = -1L
        private const val RESUME_DELAY_MS = 300L
        private const val ROTATION_DELAY_MS = 450L
        private const val MIN_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 1_000L
        private const val SNOOZE_MS = 5 * 60 * 1_000L
        private const val SECURE_MESSAGE_MS = 6_000L
        private const val GRACE_MS = 1_500L
        private const val GRACE_RETRY_MS = 150L

        private const val CHANNEL_ID = "paperscreen"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.paperscreen.action.STOP"
        const val ACTION_SNOOZE = "com.paperscreen.action.SNOOZE"
        const val ACTION_RESUME = "com.paperscreen.action.RESUME"

        /** The connected service, if the user has enabled it. Main thread only. */
        private var instance: PaperScreenAccessibilityService? = null

        private val stateListeners = mutableSetOf<(State) -> Unit>()

        val state: State
            get() {
                val service = instance ?: return State.DISABLED
                return when {
                    !service.isCapturing -> State.READY
                    service.isSnoozed -> State.SNOOZED
                    else -> State.RUNNING
                }
            }

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

        /** Handles a notification button. */
        fun onNotificationAction(context: Context, action: String?) {
            val service = instance
            if (service == null) {
                // The process was restarted (e.g. after a long sleep) and the service hasn't
                // reconnected yet. Record the choice; the service reads it when it connects.
                val settings = PaperSettings(context)
                when (action) {
                    ACTION_STOP -> {
                        settings.captureEnabled = false
                        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    }
                    ACTION_SNOOZE -> if (settings.snoozeUntil <= System.currentTimeMillis()) {
                        settings.snoozeUntil = System.currentTimeMillis() + SNOOZE_MS
                    }
                    ACTION_RESUME -> settings.snoozeUntil = 0L
                }
                return
            }
            if (!service.isCapturing) {
                // Left over from an earlier session.
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                return
            }
            when (action) {
                ACTION_STOP -> stopCapture()
                ACTION_SNOOZE -> service.snooze()
                ACTION_RESUME -> service.endSnooze()
            }
        }
    }
}
