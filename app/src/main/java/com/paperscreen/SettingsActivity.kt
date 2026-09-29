package com.paperscreen

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

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
    private lateinit var warmthSeek: SeekBar
    private lateinit var nightSwitch: Switch

    /** Re-evaluate each slider's reset button (e.g. when Night warmth takes over warmth). */
    private val resetRefreshers = mutableListOf<() -> Unit>()
    private var warmthOverrideDialog: AlertDialog? = null

    // While visible, keep the Night warmth readouts in step with the clock.
    private val main = Handler(Looper.getMainLooper())
    private val nightTick = object : Runnable {
        override fun run() {
            renderWarmthControl()
            renderNightStatus()
            main.postDelayed(this, NIGHT_TICK_MS)
        }
    }

    /** Suppresses switch listeners while the UI is being synced to state. */
    private var syncingUi = false

    private var homeRequestedAt = 0L
    private var firstRunHomePrompt = false

    /** The user asked to start, and we sent them to turn on the accessibility service first. */
    private var startAfterServiceEnabled = false

    private val stateListener: (PaperScreenAccessibilityService.State) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = PaperSettings(this)
        preview = FilterPreview(this)
        startAfterServiceEnabled = savedInstanceState?.getBoolean(STATE_START_PENDING) ?: false
        firstRunHomePrompt = savedInstanceState?.getBoolean(STATE_FIRST_RUN_HOME) ?: false

        serviceBanner = findViewById(R.id.permission_banner)
        masterSwitch = findViewById(R.id.master_switch)
        homeSwitch = findViewById(R.id.home_switch)
        statusText = findViewById(R.id.status_text)
        intervalValue = findViewById(R.id.interval_value)
        warmthValue = findViewById(R.id.warmth_value)
        contrastValue = findViewById(R.id.contrast_value)
        levelsValue = findViewById(R.id.levels_value)
        fullRefreshValue = findViewById(R.id.full_refresh_value)
        // These mirror live state (is filtering on, is PaperScreen the launcher). Restoring a
        // stale checked state after rotation would fire their listeners and start or stop things.
        masterSwitch.isSaveEnabled = false
        homeSwitch.isSaveEnabled = false

        findViewById<Button>(R.id.grant_button).setOnClickListener { openAccessibilitySettings() }
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingUi) return@setOnCheckedChangeListener
            if (checked) beginStart() else PaperScreenAccessibilityService.stopCapture()
        }

        setUpIntervalPresets()
        setUpSliders()
        setUpFeatureToggles()
        setUpHomeSwitch()
        setUpHomeOptions()
        setUpNightWarmth()
        setUpWarmthOverride()
        setUpPreviewButton()

        // First run: offer to become the home screen straight away (the system asks the user
        // to confirm); the accessibility service prompt follows once that's answered.
        if (!settings.homePromptShown) {
            settings.homePromptShown = true
            if (!isDefaultHome()) {
                firstRunHomePrompt = true
                requestHomeRole()
                return
            }
        }
        maybeShowServicePrompt()
    }

    private fun maybeShowServicePrompt() {
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
        nightTick.run() // Renders now, then every so often.
        if (startAfterServiceEnabled) {
            startAfterServiceEnabled = false
            if (isServiceEnabled()) beginStart()
        }
    }

    override fun onPause() {
        main.removeCallbacks(nightTick)
        preview.end()
        super.onPause()
    }

    override fun onStop() {
        PaperScreenAccessibilityService.removeStateListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        warmthOverrideDialog?.dismiss()
        preview.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_START_PENDING, startAfterServiceEnabled)
        outState.putBoolean(STATE_FIRST_RUN_HOME, firstRunHomePrompt)
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
        val isHome = isDefaultHome()
        syncingUi = true
        homeSwitch.isChecked = isHome
        syncingUi = false
        findViewById<View>(R.id.home_options).visibility = if (isHome) View.VISIBLE else View.GONE
    }

    /** Clock and font options for the home screen; applied when it next comes to the front. */
    private fun setUpHomeOptions() {
        findViewById<Switch>(R.id.clock_24h_switch).apply {
            isChecked = settings.clock24h ?: DateFormat.is24HourFormat(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> settings.clock24h = checked }
        }
        findViewById<Switch>(R.id.show_date_switch).apply {
            isChecked = settings.showDate
            setOnCheckedChangeListener { _, checked -> settings.showDate = checked }
        }
        findViewById<Switch>(R.id.show_seconds_switch).apply {
            isChecked = settings.showSeconds
            setOnCheckedChangeListener { _, checked -> settings.showSeconds = checked }
        }
        bindChoice(R.id.clock_size_group, ClockSize.entries, settings.clockSize, { getString(it.label) }) {
            settings.clockSize = it
        }
        bindChoice(
            R.id.home_font_group,
            HomeFont.entries,
            settings.homeFont,
            { getString(it.label) },
            typeface = { it.typeface() },
        ) { settings.homeFont = it }
    }

    /** A row of radio buttons, one per option, saving the choice as it changes. */
    private fun <T> bindChoice(
        groupId: Int,
        options: List<T>,
        selected: T,
        label: (T) -> String,
        typeface: ((T) -> Typeface)? = null,
        save: (T) -> Unit,
    ) {
        val group = findViewById<RadioGroup>(groupId)
        val minHeight = (48 * resources.displayMetrics.density).toInt()
        for (option in options) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label(option)
                textSize = 15f
                setTextColor(getColor(R.color.ink))
                typeface?.let { this.typeface = it(option) }
                this.minHeight = minHeight
            }
            group.addView(button, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (option == selected) group.check(button.id)
            button.setOnCheckedChangeListener { _, checked -> if (checked) save(option) }
        }
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

    @Deprecated("Framework Activity result API.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HOME) return
        if (firstRunHomePrompt) {
            // The unprompted first-run offer: accept the answer either way and move on.
            firstRunHomePrompt = false
            renderHome()
            maybeShowServicePrompt()
            return
        }
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
            default = PaperSettings.DEFAULT_CUSTOM_INTERVAL_MS / step,
            valueId = R.id.custom_interval_value,
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
        warmthSeek = bindSlider(
            R.id.warmth_seek,
            max = 100,
            initial = settings.warmth,
            default = PaperSettings.DEFAULT_WARMTH,
            valueId = R.id.warmth_value,
            save = {
                // Only reachable without a touch (keyboard, accessibility) while Night warmth
                // is in control; offer the switch back rather than changing warmth silently.
                if (settings.nightWarmth) {
                    renderWarmthControl()
                    offerManualWarmth()
                } else {
                    settings.warmth = it
                }
            },
            render = { warmthValue.text = getString(R.string.warmth_value, it) },
            resetEnabled = { !settings.nightWarmth },
        )
        bindSlider(
            R.id.contrast_seek,
            max = 100,
            initial = settings.contrast,
            default = PaperSettings.DEFAULT_CONTRAST,
            valueId = R.id.contrast_value,
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
            default = PaperSettings.DEFAULT_LEVELS,
            valueId = R.id.levels_value,
            save = { settings.greyLevels = it },
            render = { levelsValue.text = getString(R.string.grey_levels_value, it) },
        )
        bindSlider(
            R.id.full_refresh_seek,
            min = PaperSettings.MIN_FULL_REFRESH_EVERY,
            max = PaperSettings.MAX_FULL_REFRESH_EVERY,
            initial = settings.fullRefreshEvery,
            default = PaperSettings.DEFAULT_FULL_REFRESH_EVERY,
            valueId = R.id.full_refresh_value,
            save = { settings.fullRefreshEvery = it },
            render = { fullRefreshValue.text = resources.getQuantityString(R.plurals.full_refresh_every_value, it, it) },
        )
        val flashStep = PaperSettings.FLASH_STEP_MS
        bindSlider(
            R.id.flash_duration_seek,
            min = PaperSettings.MIN_FLASH_MS / flashStep,
            max = PaperSettings.MAX_FLASH_MS / flashStep,
            initial = settings.flashDurationMs / flashStep,
            default = PaperSettings.DEFAULT_FLASH_MS / flashStep,
            valueId = R.id.flash_duration_value,
            save = { settings.flashDurationMs = it * flashStep },
            render = { valueText(R.id.flash_duration_value).text = getString(R.string.flash_duration_value, it * flashStep) },
        )
        bindSlider(
            R.id.ghost_opacity_seek,
            min = PaperSettings.MIN_GHOST_OPACITY,
            max = PaperSettings.MAX_GHOST_OPACITY,
            initial = settings.ghostOpacity,
            default = PaperSettings.DEFAULT_GHOST_OPACITY,
            valueId = R.id.ghost_opacity_value,
            save = { settings.ghostOpacity = it },
            render = { valueText(R.id.ghost_opacity_value).text = getString(R.string.ghost_opacity_value, it) },
        )
        val fadeStep = PaperSettings.GHOST_FADE_STEP_MS
        bindSlider(
            R.id.ghost_fade_seek,
            min = PaperSettings.MIN_GHOST_FADE_MS / fadeStep,
            max = PaperSettings.MAX_GHOST_FADE_MS / fadeStep,
            initial = settings.ghostFadeMs / fadeStep,
            default = PaperSettings.DEFAULT_GHOST_FADE_MS / fadeStep,
            valueId = R.id.ghost_fade_value,
            save = { settings.ghostFadeMs = it * fadeStep },
            render = { valueText(R.id.ghost_fade_value).text = getString(R.string.ghost_fade_value, it * fadeStep) },
        )
    }

    // --- Night warmth ----------------------------------------------------------------------

    private fun setUpNightWarmth() {
        nightSwitch = findViewById(R.id.night_switch)
        bindFeature(R.id.night_switch, R.id.night_options, settings.nightWarmth) {
            settings.nightWarmth = it
            renderWarmthControl()
            renderNightStatus()
        }
        val customTimes = findViewById<View>(R.id.night_custom_times)
        customTimes.visibility = if (settings.nightSchedule == NightScheduleMode.CUSTOM) View.VISIBLE else View.GONE
        bindChoice(R.id.night_schedule_group, NightScheduleMode.entries, settings.nightSchedule, { getString(it.label) }) {
            settings.nightSchedule = it
            customTimes.visibility = if (it == NightScheduleMode.CUSTOM) View.VISIBLE else View.GONE
            renderWarmthControl()
            renderNightStatus()
        }
        bindTime(R.id.night_start_button, { settings.nightStart }) { settings.nightStart = it }
        bindTime(R.id.night_end_button, { settings.nightEnd }) { settings.nightEnd = it }
        bindSlider(
            R.id.day_warmth_seek,
            max = 100,
            initial = settings.dayWarmth,
            default = PaperSettings.DEFAULT_DAY_WARMTH,
            valueId = R.id.day_warmth_value,
            save = {
                settings.dayWarmth = it
                renderWarmthControl()
                renderNightStatus()
            },
            render = { valueText(R.id.day_warmth_value).text = getString(R.string.warmth_value, it) },
        )
        bindSlider(
            R.id.night_warmth_seek,
            max = 100,
            initial = settings.nightWarmthLevel,
            default = PaperSettings.DEFAULT_NIGHT_WARMTH_LEVEL,
            valueId = R.id.night_warmth_value,
            save = {
                settings.nightWarmthLevel = it
                renderWarmthControl()
                renderNightStatus()
            },
            render = { valueText(R.id.night_warmth_value).text = getString(R.string.warmth_value, it) },
        )
        findViewById<Switch>(R.id.night_transition_switch).apply {
            isChecked = settings.nightTransition
            setOnCheckedChangeListener { _, checked ->
                settings.nightTransition = checked
                renderWarmthControl()
                renderNightStatus()
            }
        }
    }

    /** A button showing a time of day that opens the system time picker. */
    private fun bindTime(buttonId: Int, current: () -> Int, save: (Int) -> Unit) {
        val button = findViewById<Button>(buttonId)
        button.text = formatMinutes(current())
        button.setOnClickListener {
            val minutes = current()
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    save(hour * 60 + minute)
                    button.text = formatMinutes(hour * 60 + minute)
                    renderWarmthControl()
                    renderNightStatus()
                },
                minutes / 60,
                minutes % 60,
                DateFormat.is24HourFormat(this),
            ).show()
        }
    }

    private fun formatMinutes(minutes: Int): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return DateFormat.getTimeFormat(this).format(calendar.time)
    }

    /** What Night warmth is following, and the warmth it gives right now. */
    private fun renderNightStatus() {
        if (!settings.nightWarmth) return
        val status = settings.nightWarmthSchedule.status()
        val place = status.zone?.substringAfterLast('/')?.replace('_', ' ').orEmpty()
        val sunset = status.sunset
        val sunrise = status.sunrise
        valueText(R.id.night_status).text = when (status.source) {
            NightWarmth.Source.CUSTOM -> getString(R.string.night_status_custom, status.warmth)
            NightWarmth.Source.NIGHT_LIGHT -> getString(R.string.night_status_night_light, status.warmth)
            NightWarmth.Source.SUN_ESTIMATE -> if (sunset != null && sunrise != null) {
                getString(R.string.night_status_sun, formatMinutes(sunset), formatMinutes(sunrise), place, status.warmth)
            } else {
                getString(R.string.night_status_polar, place, status.warmth)
            }
        }
    }

    /**
     * While Night warmth is on, the Warmth slider shows the warmth in use, dimmed, with a note;
     * otherwise it's the ordinary manual control.
     */
    private fun renderWarmthControl() {
        val controlled = settings.nightWarmth
        findViewById<View>(R.id.warmth_controlled).visibility = if (controlled) View.VISIBLE else View.GONE
        val alpha = if (controlled) DIMMED_ALPHA else 1f
        warmthSeek.alpha = alpha
        warmthValue.alpha = alpha
        warmthSeek.progress = if (controlled) settings.effectiveWarmth() else settings.warmth
        resetRefreshers.forEach { it() }
    }

    /** Touching the Warmth slider while Night warmth is on offers manual control back. */
    @SuppressLint("ClickableViewAccessibility") // Accessibility changes are caught in save.
    private fun setUpWarmthOverride() {
        warmthSeek.setOnTouchListener { _, event ->
            if (!settings.nightWarmth) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) offerManualWarmth()
            true
        }
    }

    private fun offerManualWarmth() {
        if (warmthOverrideDialog?.isShowing == true) return
        warmthOverrideDialog = AlertDialog.Builder(this)
            .setTitle(R.string.warmth_override_title)
            .setMessage(R.string.warmth_override_message)
            .setPositiveButton(R.string.warmth_override_confirm) { _, _ ->
                // The switch's listener saves the setting and restores the manual slider.
                nightSwitch.isChecked = false
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /**
     * Binds a slider, saving as it moves, with a reset button beside [valueId] that appears
     * while the value differs from [default] and animates the slider back to it.
     */
    private fun bindSlider(
        id: Int,
        min: Int = 0,
        max: Int,
        initial: Int,
        default: Int,
        valueId: Int,
        save: (Int) -> Unit,
        render: (Int) -> Unit,
        resetEnabled: () -> Boolean = { true },
    ): SeekBar {
        val seek = findViewById<SeekBar>(id)
        seek.min = min
        seek.max = max
        seek.progress = initial.coerceIn(min, max)
        render(seek.progress)

        val reset = addResetButton(findViewById(valueId))
        var animator: ObjectAnimator? = null
        val refreshReset = {
            // INVISIBLE, not GONE, so the value beside it doesn't shift as it comes and goes.
            reset.visibility = if (seek.progress != default && resetEnabled()) View.VISIBLE else View.INVISIBLE
        }
        resetRefreshers += refreshReset
        refreshReset()
        reset.setOnClickListener {
            animator?.cancel()
            animator = ObjectAnimator.ofInt(seek, "progress", seek.progress, default).apply {
                duration = RESET_ANIMATION_MS
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        animator = null
                        if (!cancelled) save(default)
                    }
                })
                start()
            }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                render(progress)
                refreshReset()
                if (fromUser) save(progress)
            }

            // Grabbing the slider mid-reset leaves it with the finger.
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                animator?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        return seek
    }

    /** A small circular-arrow button placed after [value] in its label row. */
    private fun addResetButton(value: TextView): ImageButton {
        val row = value.parent as LinearLayout
        row.gravity = Gravity.CENTER_VERTICAL
        val label = row.getChildAt(0) as? TextView
        val size = dp(RESET_BUTTON_DP)
        // Negative vertical margins keep the rows as tall as before despite the touch target.
        val overhang = -(size - dp(ROW_TEXT_DP)) / 2
        val selectable = TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        val button = ImageButton(this).apply {
            setImageResource(R.drawable.ic_reset)
            imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setBackgroundResource(selectable.resourceId)
            contentDescription = getString(R.string.reset_to_default, label?.text ?: "")
            tooltipText = contentDescription
            visibility = View.INVISIBLE
        }
        val params = LinearLayout.LayoutParams(size, size).apply {
            topMargin = overhang
            bottomMargin = overhang
            marginStart = dp(4)
            marginEnd = -dp(8) // Lines the arrow up with the slider's end.
        }
        row.addView(button, row.indexOfChild(value) + 1, params)
        return button
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
        private const val NIGHT_TICK_MS = 30_000L
        private const val RESET_ANIMATION_MS = 250L
        private const val RESET_BUTTON_DP = 40
        private const val ROW_TEXT_DP = 22
        private const val DIMMED_ALPHA = 0.4f
        private const val STATE_START_PENDING = "start_pending"
        private const val STATE_FIRST_RUN_HOME = "first_run_home_prompt"
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
