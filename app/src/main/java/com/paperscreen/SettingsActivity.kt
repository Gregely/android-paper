package com.paperscreen

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var settings: PaperSettings
    private lateinit var preview: FilterPreview

    private lateinit var serviceBanner: View
    private lateinit var masterSwitch: Switch
    private lateinit var homeSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var intervalValue: TextView
    private lateinit var customInterval: View
    private lateinit var warmthValue: TextView
    private lateinit var contrastValue: TextView
    private lateinit var levelsValue: TextView
    private lateinit var fullRefreshValue: TextView

    /** Suppresses switch listeners while the UI is being synced to state. */
    private var syncingUi = false

    private var homeRequestedAt = 0L

    /** The user asked to start, and we sent them to turn on the accessibility service first. */
    private var startAfterServiceEnabled = false

    private val stateListener: (PaperScreenAccessibilityService.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = PaperSettings(this)
        preview = FilterPreview(this)
        startAfterServiceEnabled = savedInstanceState?.getBoolean(STATE_START_PENDING) ?: false

        serviceBanner = findViewById(R.id.permission_banner)
        masterSwitch = findViewById(R.id.master_switch)
        homeSwitch = findViewById(R.id.home_switch)
        statusText = findViewById(R.id.status_text)
        intervalValue = findViewById(R.id.interval_value)
        warmthValue = findViewById(R.id.warmth_value)
        contrastValue = findViewById(R.id.contrast_value)
        levelsValue = findViewById(R.id.levels_value)
        fullRefreshValue = findViewById(R.id.full_refresh_value)

        findViewById<Button>(R.id.grant_button).setOnClickListener { openAccessibilitySettings() }
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingUi) return@setOnCheckedChangeListener
            if (checked) beginStart() else PaperScreenAccessibilityService.stopCapture()
        }

        setUpIntervalPresets()
        setUpSliders()
        setUpFeatureToggles()
        setUpHomeSwitch()
        setUpPreviewButton()

        if (!isServiceEnabled() && !settings.servicePromptShown) {
            settings.servicePromptShown = true
            showServiceDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        PaperScreenAccessibilityService.addStateListener(stateListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        renderHome() // The default launcher may have changed elsewhere.
        if (startAfterServiceEnabled) {
            startAfterServiceEnabled = false
            if (isServiceEnabled()) beginStart()
        }
    }

    override fun onPause() {
        preview.end()
        super.onPause()
    }

    override fun onStop() {
        PaperScreenAccessibilityService.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_START_PENDING, startAfterServiceEnabled)
    }

    // --- Start flow: accessibility service → notification permission → start ------------

    private fun beginStart() {
        if (isOn(PaperScreenAccessibilityService.state)) return
        if (!isServiceEnabled()) {
            startAfterServiceEnabled = true
            showServiceDialog()
            render()
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !settings.notificationPromptShown
        ) {
            // Asked once: filtering works without it, only the Stop / Pause notification is lost.
            settings.notificationPromptShown = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) start()
    }

    private fun start() {
        if (!PaperScreenAccessibilityService.startCapture()) {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun showServiceDialog() {
        // Sideloaded apps can't enable accessibility services until restricted settings are allowed.
        val message = getString(R.string.service_dialog_message) + getString(R.string.service_dialog_restricted)
        AlertDialog.Builder(this)
            .setTitle(R.string.service_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.not_now) { _, _ -> abandonStart() }
            .setOnCancelListener { abandonStart() }
            .show()
    }

    private fun abandonStart() {
        startAfterServiceEnabled = false
        render()
    }

    private fun openAccessibilitySettings() {
        val component = serviceComponent().flattenToString()
        // Many Settings builds scroll to and highlight the entry named by these extras.
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
        startActivity(intent)
    }

    /**
     * Whether the user has turned the service on. The system setting is checked as well as
     * the live connection, since the service may still be binding right after a process start.
     */
    private fun isServiceEnabled(): Boolean {
        if (PaperScreenAccessibilityService.state != PaperScreenAccessibilityService.State.DISABLED) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = serviceComponent()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun serviceComponent() = ComponentName(this, PaperScreenAccessibilityService::class.java)

    // --- Home screen -----------------------------------------------------------------------

    /** Reflects whether PaperScreen is the default launcher (holds the Home role). */
    private fun setUpHomeSwitch() {
        renderHome()
        homeSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingUi) return@setOnCheckedChangeListener
            if (checked) requestHomeRole() else openHomeSettings()
            renderHome() // Stays as it is until the system confirms a change.
        }
    }

    private fun renderHome() {
        syncingUi = true
        homeSwitch.isChecked = isDefaultHome()
        syncingUi = false
    }

    private fun isDefaultHome(): Boolean =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /** Shows the system's "set as default home app" prompt. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
            openHomeSettings()
            return
        }
        homeRequestedAt = SystemClock.uptimeMillis()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME)
    }

    @Deprecated("Framework Activity result API; this app avoids AndroidX.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HOME) return
        // A refusal this fast means the system didn't show its prompt (e.g. after the user
        // declined it twice); fall back to the default-apps screen.
        if (!isDefaultHome() && SystemClock.uptimeMillis() - homeRequestedAt < INSTANT_REFUSAL_MS) {
            openHomeSettings()
        }
        renderHome()
    }

    /** The system's "Default home app" setting: the only way to switch launchers away. */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: RuntimeException) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    // --- Controls ------------------------------------------------------------------------

    private fun render() {
        val state = PaperScreenAccessibilityService.state
        serviceBanner.visibility = if (isServiceEnabled()) View.GONE else View.VISIBLE
        syncingUi = true
        masterSwitch.isChecked = isOn(state)
        syncingUi = false
        statusText.setText(
            when (state) {
                PaperScreenAccessibilityService.State.RUNNING -> R.string.status_running
                PaperScreenAccessibilityService.State.SNOOZED -> R.string.status_snoozed
                else -> R.string.status_stopped
            },
        )
    }

    private fun isOn(state: PaperScreenAccessibilityService.State) =
        state == PaperScreenAccessibilityService.State.RUNNING ||
            state == PaperScreenAccessibilityService.State.SNOOZED

    /** One radio button per preset; Custom reveals its own slider. Both are saved as they change. */
    private fun setUpIntervalPresets() {
        val group = findViewById<RadioGroup>(R.id.interval_presets)
        customInterval = findViewById(R.id.custom_interval)
        val customValue = findViewById<TextView>(R.id.custom_interval_value)
        val minHeight = (48 * resources.displayMetrics.density).toInt()
        var selectedId = View.NO_ID
        for (preset in RefreshPreset.entries) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                tag = preset
                text = preset.ms?.let { getString(R.string.preset_option, getString(preset.label), it) }
                    ?: getString(preset.label)
                textSize = 15f
                setTextColor(getColor(R.color.ink))
                this.minHeight = minHeight
            }
            group.addView(button, RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            if (preset == settings.refreshPreset) selectedId = button.id
        }
        group.check(selectedId)
        group.setOnCheckedChangeListener { radios, checkedId ->
            val preset = radios.findViewById<RadioButton>(checkedId)?.tag as? RefreshPreset
                ?: return@setOnCheckedChangeListener
            settings.refreshPreset = preset
            renderInterval()
        }

        val step = PaperSettings.CUSTOM_INTERVAL_STEP_MS
        bindSlider(
            R.id.custom_interval_seek,
            min = PaperSettings.MIN_CUSTOM_INTERVAL_MS / step,
            max = PaperSettings.MAX_CUSTOM_INTERVAL_MS / step,
            initial = settings.customIntervalMs / step,
            save = {
                settings.customIntervalMs = it * step
                renderInterval()
            },
            render = { customValue.text = getString(R.string.custom_interval_value, it * step) },
        )
        renderInterval()
    }

    private fun renderInterval() {
        val preset = settings.refreshPreset
        customInterval.visibility = if (preset == RefreshPreset.CUSTOM) View.VISIBLE else View.GONE
        intervalValue.text = getString(R.string.refresh_interval_value, getString(preset.label), settings.refreshIntervalMs)
    }

    private fun setUpSliders() {
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
        bindSlider(
            R.id.full_refresh_seek,
            min = PaperSettings.MIN_FULL_REFRESH_EVERY,
            max = PaperSettings.MAX_FULL_REFRESH_EVERY,
            initial = settings.fullRefreshEvery,
            save = { settings.fullRefreshEvery = it },
            render = { fullRefreshValue.text = resources.getQuantityString(R.plurals.full_refresh_every_value, it, it) },
        )
        val flashStep = PaperSettings.FLASH_STEP_MS
        bindSlider(
            R.id.flash_duration_seek,
            min = PaperSettings.MIN_FLASH_MS / flashStep,
            max = PaperSettings.MAX_FLASH_MS / flashStep,
            initial = settings.flashDurationMs / flashStep,
            save = { settings.flashDurationMs = it * flashStep },
            render = { valueText(R.id.flash_duration_value).text = getString(R.string.flash_duration_value, it * flashStep) },
        )
        bindSlider(
            R.id.ghost_opacity_seek,
            min = PaperSettings.MIN_GHOST_OPACITY,
            max = PaperSettings.MAX_GHOST_OPACITY,
            initial = settings.ghostOpacity,
            save = { settings.ghostOpacity = it },
            render = { valueText(R.id.ghost_opacity_value).text = getString(R.string.ghost_opacity_value, it) },
        )
        val fadeStep = PaperSettings.GHOST_FADE_STEP_MS
        bindSlider(
            R.id.ghost_fade_seek,
            min = PaperSettings.MIN_GHOST_FADE_MS / fadeStep,
            max = PaperSettings.MAX_GHOST_FADE_MS / fadeStep,
            initial = settings.ghostFadeMs / fadeStep,
            save = { settings.ghostFadeMs = it * fadeStep },
            render = { valueText(R.id.ghost_fade_value).text = getString(R.string.ghost_fade_value, it * fadeStep) },
        )
    }

    private fun valueText(id: Int): TextView = findViewById(id)

    /** Optional features: each switch shows its sub-settings only while it's on. */
    private fun setUpFeatureToggles() {
        bindFeature(R.id.posterize_switch, R.id.posterize_options, settings.posterize) { settings.posterize = it }
        bindFeature(R.id.ghosting_switch, R.id.ghosting_options, settings.ghosting) { settings.ghosting = it }
        bindFeature(R.id.full_refresh_switch, R.id.full_refresh_options, settings.fullRefresh) {
            settings.fullRefresh = it
        }
        // Refresh on change has no sub-settings; its description stays visible either way.
        bindFeature(R.id.on_change_switch, null, settings.refreshOnChange) { settings.refreshOnChange = it }
    }

    private fun bindFeature(switchId: Int, optionsId: Int?, initial: Boolean, save: (Boolean) -> Unit) {
        val toggle = findViewById<Switch>(switchId)
        val options = optionsId?.let { findViewById<View>(it) }
        toggle.isChecked = initial
        options?.visibility = if (initial) View.VISIBLE else View.GONE
        toggle.setOnCheckedChangeListener { _, checked ->
            save(checked)
            options?.visibility = if (checked) View.VISIBLE else View.GONE
        }
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

    @SuppressLint("ClickableViewAccessibility") // The button still performs its own click.
    private fun setUpPreviewButton() {
        findViewById<Button>(R.id.preview_button).setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Keep the ScrollView from stealing the gesture while the finger is held.
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    val invertMs = if (settings.fullRefresh) settings.flashDurationMs.toLong() else 0L
                    preview.begin(settings.filterParams(), invertMs)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> preview.end()
            }
            false
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
        private const val REQUEST_HOME = 2
        private const val INSTANT_REFUSAL_MS = 500L
        private const val STATE_START_PENDING = "start_pending"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
