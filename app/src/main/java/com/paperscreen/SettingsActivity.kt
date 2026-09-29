package com.paperscreen

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var settings: PaperSettings
    private lateinit var preview: FilterPreview

    private lateinit var permissionBanner: View
    private lateinit var masterSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var intervalValue: TextView
    private lateinit var warmthValue: TextView
    private lateinit var contrastValue: TextView
    private lateinit var levelsValue: TextView

    /** Suppresses switch listeners while the UI is being synced to state. */
    private var syncingUi = false

    /** The user asked to start, and we sent them to grant the overlay permission first. */
    private var startAfterOverlayGrant = false

    private val runningListener: (Boolean) -> Unit = { renderRunning(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = PaperSettings(this)
        preview = FilterPreview(this)
        startAfterOverlayGrant = savedInstanceState?.getBoolean(STATE_START_PENDING) ?: false

        permissionBanner = findViewById(R.id.permission_banner)
        masterSwitch = findViewById(R.id.master_switch)
        statusText = findViewById(R.id.status_text)
        intervalValue = findViewById(R.id.interval_value)
        warmthValue = findViewById(R.id.warmth_value)
        contrastValue = findViewById(R.id.contrast_value)
        levelsValue = findViewById(R.id.levels_value)

        findViewById<Button>(R.id.grant_button).setOnClickListener { openOverlaySettings() }
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingUi) return@setOnCheckedChangeListener
            if (checked) beginStart() else PaperScreenService.stop(this)
        }

        setUpSliders()
        setUpOnChangeSwitch()
        setUpPreviewButton()

        if (!Settings.canDrawOverlays(this) && !settings.overlayPromptShown) {
            settings.overlayPromptShown = true
            showOverlayDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        PaperScreenService.addStateListener(runningListener)
        renderRunning(PaperScreenService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        val canDraw = Settings.canDrawOverlays(this)
        permissionBanner.visibility = if (canDraw) View.GONE else View.VISIBLE
        if (startAfterOverlayGrant) {
            startAfterOverlayGrant = false
            if (canDraw) beginStart() else renderRunning(PaperScreenService.isRunning)
        }
    }

    override fun onPause() {
        preview.end()
        super.onPause()
    }

    override fun onStop() {
        PaperScreenService.removeStateListener(runningListener)
        super.onStop()
    }

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_START_PENDING, startAfterOverlayGrant)
    }

    // --- Start flow: overlay permission → notification permission → capture consent -------

    private fun beginStart() {
        if (PaperScreenService.isRunning) return
        if (!Settings.canDrawOverlays(this)) {
            startAfterOverlayGrant = true
            showOverlayDialog()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The service runs either way; this only makes its stop toggle visible.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) requestCapture()
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole-screen only: a single-app capture wouldn't match what's under the overlay.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Framework Activity result API; this app avoids AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            PaperScreenService.start(this, resultCode, data)
        } else {
            Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show()
            renderRunning(PaperScreenService.isRunning)
        }
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.not_now) { _, _ -> abandonStart() }
            .setOnCancelListener { abandonStart() }
            .show()
    }

    private fun abandonStart() {
        startAfterOverlayGrant = false
        renderRunning(PaperScreenService.isRunning)
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            // Some builds don't support the package-specific page.
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    // --- Controls ------------------------------------------------------------------------

    private fun renderRunning(running: Boolean) {
        syncingUi = true
        masterSwitch.isChecked = running
        syncingUi = false
        statusText.setText(if (running) R.string.status_running else R.string.status_stopped)
    }

    private fun setUpSliders() {
        val step = PaperSettings.INTERVAL_STEP_MS
        val minInterval = PaperSettings.MIN_INTERVAL_MS
        bindSlider(
            R.id.interval_seek,
            max = (PaperSettings.MAX_INTERVAL_MS - minInterval) / step,
            initial = (settings.refreshIntervalMs - minInterval) / step,
            save = { settings.refreshIntervalMs = minInterval + it * step },
            render = { intervalValue.text = getString(R.string.refresh_interval_value, minInterval + it * step) },
        )
        bindSlider(
            R.id.warmth_seek,
            max = 100,
            initial = settings.warmth,
            save = { settings.warmth = it },
            render = { warmthValue.text = getString(R.string.warmth_value, it) },
        )
        bindSlider(
            R.id.contrast_seek,
            max = 100,
            initial = settings.contrast,
            save = { settings.contrast = it },
            render = {
                contrastValue.text = getString(
                    R.string.contrast_value,
                    it,
                    EinkFilter.inkLevel(it),
                    EinkFilter.paperLevel(it),
                )
            },
        )
        bindSlider(
            R.id.levels_seek,
            min = PaperSettings.MIN_LEVELS,
            max = PaperSettings.MAX_LEVELS,
            initial = settings.greyLevels,
            save = { settings.greyLevels = it },
            render = { levelsValue.text = getString(R.string.grey_levels_value, it) },
        )
    }

    private fun bindSlider(
        id: Int,
        min: Int = 0,
        max: Int,
        initial: Int,
        save: (Int) -> Unit,
        render: (Int) -> Unit,
    ) {
        val seek = findViewById<SeekBar>(id)
        seek.min = min
        seek.max = max
        seek.progress = initial.coerceIn(min, max)
        render(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                render(progress)
                if (fromUser) save(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setUpOnChangeSwitch() {
        val toggle = findViewById<Switch>(R.id.on_change_switch)
        toggle.isChecked = settings.refreshOnChange
        toggle.setOnCheckedChangeListener { _, checked -> settings.refreshOnChange = checked }
    }

    @SuppressLint("ClickableViewAccessibility") // The button still performs its own click.
    private fun setUpPreviewButton() {
        findViewById<Button>(R.id.preview_button).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Keep the ScrollView from stealing the gesture while the finger is held.
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    preview.begin(settings.filterParams())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> preview.end()
            }
            false
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
        private const val REQUEST_NOTIFICATIONS = 2
        private const val STATE_START_PENDING = "start_pending"
    }
}
