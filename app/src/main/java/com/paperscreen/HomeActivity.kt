package com.paperscreen

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale

/**
 * A minimal home screen that reads like a page of an e-reader: clock, date, up to five
 * favourite apps as text, and a searchable alphabetical list of every app. No icons,
 * wallpaper, widgets, dock or folders. Colours follow the filter's warmth setting.
 */
class HomeActivity : Activity() {

    private lateinit var settings: PaperSettings
    private lateinit var catalog: AppCatalog
    private lateinit var launcherApps: LauncherApps

    private lateinit var root: View
    private lateinit var header: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var favouritesRow: LinearLayout
    private lateinit var rules: List<View>
    private lateinit var search: EditText
    private lateinit var list: ListView
    private val listAdapter = AppAdapter()

    private var ink = Color.rgb(0x33, 0x33, 0x33)
    private var paper = Color.WHITE
    private var rule = Color.rgb(0xDD, 0xDD, 0xDD)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        settings = PaperSettings(this)
        launcherApps = getSystemService(LauncherApps::class.java)

        root = findViewById(R.id.home_root)
        header = findViewById(R.id.home_header)
        clock = findViewById(R.id.home_clock)
        date = findViewById(R.id.home_date)
        favouritesRow = findViewById(R.id.home_favourites)
        rules = listOf(findViewById(R.id.home_rule_top), findViewById(R.id.home_rule_bottom))
        search = findViewById(R.id.home_search)
        list = findViewById(R.id.home_apps)

        list.adapter = listAdapter
        list.setOnItemClickListener { _, _, position, _ -> launch(listAdapter.getItem(position)) }
        setUpSearch()
        setUpFavouriteSlots()

        catalog = AppCatalog(this) {
            applySearch()
            renderFavourites()
        }
        catalog.start()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK) // Once a minute.
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        registerReceiver(timeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        updateClock()
    }

    override fun onResume() {
        super.onResume()
        applyColors() // Warmth may have changed in settings.
        renderFavourites()
    }

    override fun onStop() {
        unregisterReceiver(timeReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        catalog.stop()
        super.onDestroy()
    }

    /** The home button while already home: back to a clean page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        search.text.clear()
        search.clearFocus()
        hideKeyboard()
        list.setSelection(0)
    }

    /** Back on a home screen does nothing, except clearing a search in progress. */
    @Deprecated("Framework back handling; this app avoids AndroidX.")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) {
            search.text.clear()
            hideKeyboard()
        }
    }

    // --- Clock --------------------------------------------------------------------------

    private fun updateClock() {
        val locale = Locale.getDefault()
        val skeleton = if (DateFormat.is24HourFormat(this)) "Hm" else "hm"
        // Hours and minutes only: drop the AM/PM (or day-period) marker.
        val timePattern = DateFormat.getBestDateTimePattern(locale, skeleton)
            .replace(Regex("\\s*[aBb]+\\s*"), "")
        val now = Date()
        clock.text = DateFormat.format(timePattern, now)
        date.text = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), now)
    }

    // --- Favourites ---------------------------------------------------------------------

    private fun setUpFavouriteSlots() {
        repeat(Favourites.SLOTS) { slot ->
            val label = TextView(this).apply {
                setTextAppearance(R.style.HomeText)
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    .use { it.getDrawable(0) }
                setOnClickListener {
                    val app = catalog.find(settings.favourites[slot])
                    if (app != null) launch(app) else pickFavourite(slot)
                }
                setOnLongClickListener {
                    pickFavourite(slot)
                    true
                }
            }
            favouritesRow.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun renderFavourites() {
        val stored = settings.favourites
        for (slot in 0 until favouritesRow.childCount) {
            val view = favouritesRow.getChildAt(slot) as TextView
            val app = catalog.find(stored[slot])
            view.text = app?.label ?: getString(R.string.home_add_favourite)
            view.alpha = if (app != null) 1f else EMPTY_SLOT_ALPHA
            view.contentDescription = app?.label ?: getString(R.string.home_empty_slot_description)
        }
    }

    /** Lets the user choose (or clear) the app in a favourite slot. */
    private fun pickFavourite(slot: Int) {
        val apps = catalog.apps
        val current = catalog.find(settings.favourites[slot])
        val adapter = AppAdapter().apply { submit(apps) }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.home_pick_favourite)
            .setAdapter(adapter) { _, which -> setFavourite(slot, apps[which].component.flattenToString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (current != null) {
            builder.setNeutralButton(R.string.home_remove_favourite) { _, _ -> setFavourite(slot, null) }
        }
        builder.show()
    }

    private fun setFavourite(slot: Int, component: String?) {
        settings.favourites = settings.favourites.toMutableList().also { it[slot] = component }
        renderFavourites()
    }

    // --- Search and list ----------------------------------------------------------------

    private fun setUpSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applySearch()
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO && listAdapter.count > 0) {
                launch(listAdapter.getItem(0))
                true
            } else {
                false
            }
        }
    }

    private fun applySearch() {
        val query = search.text.toString()
        listAdapter.submit(AppSearch.filter(catalog.apps, query) { it.label })
        // Give the results the whole page while typing.
        header.visibility = if (query.isBlank()) View.VISIBLE else View.GONE
    }

    private fun launch(app: AppCatalog.App) {
        try {
            launcherApps.startMainActivity(app.component, app.user, null, null)
            if (search.text.isNotEmpty()) search.text.clear()
            hideKeyboard()
        } catch (e: RuntimeException) {
            Toast.makeText(this, getString(R.string.home_launch_failed, app.label), Toast.LENGTH_SHORT).show()
            catalog.reload()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // --- Colours ------------------------------------------------------------------------

    /** White page and #333 ink, tinted like the filter's output so both match. */
    private fun applyColors() {
        val warmth = settings.warmth
        paper = EinkFilter.tint(0xFF, warmth)
        ink = EinkFilter.tint(0x33, warmth)
        rule = EinkFilter.tint(0xDD, warmth)

        root.setBackgroundColor(paper)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        clock.setTextColor(ink)
        date.setTextColor(ink)
        for (i in 0 until favouritesRow.childCount) (favouritesRow.getChildAt(i) as TextView).setTextColor(ink)
        rules.forEach { it.setBackgroundColor(rule) }
        search.setTextColor(ink)
        search.setHintTextColor(Color.argb(0x80, Color.red(ink), Color.green(ink), Color.blue(ink)))
        search.backgroundTintList = ColorStateList.valueOf(rule)
        listAdapter.notifyDataSetChanged()
    }

    /** App names as plain text rows, in the page's ink colour. */
    private inner class AppAdapter : BaseAdapter() {
        private var items: List<AppCatalog.App> = emptyList()

        fun submit(apps: List<AppCatalog.App>) {
            items = apps
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_home_app, parent, false)) as TextView
            view.text = items[position].label
            view.setTextColor(ink)
            return view
        }
    }

    private companion object {
        const val EMPTY_SLOT_ALPHA = 0.45f
    }
}
