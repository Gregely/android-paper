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
import android.text.TextUtils
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
 * A minimal home screen that reads like a page of a book, in two views:
 *
 * - **Home page:** clock and date at the top, up to six favourite apps as centred text in
 *   the middle, and "Search apps" at the bottom.
 * - **App drawer** (opened from "Search apps"): a search field, the alphabetical app list
 *   with letter headings, and an A–Z index strip. Back or Home returns to the home page.
 *
 * No icons, wallpaper, widgets, dock or folders. Colours follow the filter's warmth setting.
 */
class HomeActivity : Activity() {

    private lateinit var settings: PaperSettings
    private lateinit var catalog: AppCatalog
    private lateinit var launcherApps: LauncherApps

    private lateinit var root: View
    private lateinit var homePage: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var rule: View
    private lateinit var favouritesList: LinearLayout
    private lateinit var openDrawer: TextView

    private lateinit var drawer: View
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var index: AlphabetStrip
    private val drawerAdapter = DrawerAdapter()

    private var ink = Color.rgb(0x33, 0x33, 0x33)
    private var faintInk = Color.argb(0x80, 0x33, 0x33, 0x33)
    private var paper = Color.WHITE
    private var ruleColor = Color.rgb(0xDD, 0xDD, 0xDD)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    private val isDrawerOpen: Boolean get() = drawer.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        settings = PaperSettings(this)
        launcherApps = getSystemService(LauncherApps::class.java)

        root = findViewById(R.id.home_root)
        homePage = findViewById(R.id.home_page)
        clock = findViewById(R.id.home_clock)
        date = findViewById(R.id.home_date)
        rule = findViewById(R.id.home_rule)
        favouritesList = findViewById(R.id.home_favourites)
        openDrawer = findViewById(R.id.home_open_drawer)
        drawer = findViewById(R.id.home_drawer)
        search = findViewById(R.id.home_search)
        list = findViewById(R.id.home_apps)
        index = findViewById(R.id.home_index)

        openDrawer.setOnClickListener { openDrawer() }
        list.adapter = drawerAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            (drawerAdapter.getItem(position) as? Row.App)?.let { launch(it.app) }
        }
        index.onLetter = { letter -> drawerAdapter.positionOf(letter)?.let { list.setSelectionFromTop(it, 0) } }
        setUpSearch()

        catalog = AppCatalog(this) {
            refreshDrawer()
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
        // Coming back home (from an app, or the screen turning on) always shows the home page.
        closeDrawer()
        super.onStop()
    }

    override fun onDestroy() {
        catalog.stop()
        super.onDestroy()
    }

    /** The home button: back to the home page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeDrawer()
    }

    /** Back closes the drawer; on the home page it does nothing, as on any launcher. */
    @Deprecated("Framework back handling; this app avoids AndroidX.")
    override fun onBackPressed() {
        closeDrawer()
    }

    // --- Home page ----------------------------------------------------------------------

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

    /** Favourites that are still installed, in the user's order. */
    private fun liveFavourites(): List<AppCatalog.App> = settings.favourites.mapNotNull(catalog::find)

    /** One centred label per favourite, then "+ Add app" while there's room for more. */
    private fun renderFavourites() {
        favouritesList.removeAllViews()
        if (catalog.apps.isEmpty()) return // Still loading; nothing to resolve against yet.
        val favourites = liveFavourites()
        favourites.forEachIndexed { i, app ->
            favouritesList.addView(favouriteLabel(app.label, 22f, ink).apply {
                setOnClickListener { launch(app) }
                setOnLongClickListener {
                    pickApp(replacing = i)
                    true
                }
            })
        }
        if (favourites.size < Favourites.MAX) {
            favouritesList.addView(favouriteLabel(getString(R.string.home_add_favourite), 16f, faintInk).apply {
                setOnClickListener { pickApp(replacing = null) }
            })
        }
    }

    private fun favouriteLabel(text: String, sizeSp: Float, color: Int) = TextView(this).apply {
        setTextAppearance(R.style.HomeText)
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val padV = (12 * resources.displayMetrics.density).toInt()
        setPadding(0, padV, 0, padV)
        background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            .use { it.getDrawable(0) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Chooses an app to add (when [replacing] is null) or to put in place of favourite
     * [replacing]; the latter also offers Remove.
     */
    private fun pickApp(replacing: Int?) {
        val apps = catalog.apps
        val adapter = DrawerAdapter().apply { submitPlain(apps) }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (replacing == null) R.string.home_add_favourite_title else R.string.home_pick_favourite)
            .setAdapter(adapter) { _, which ->
                val chosen = apps[which].component.flattenToString()
                updateFavourites { favourites ->
                    if (replacing == null) favourites += chosen else favourites[replacing] = chosen
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (replacing != null) {
            builder.setNeutralButton(R.string.home_remove_favourite) { _, _ ->
                updateFavourites { it.removeAt(replacing) }
            }
        }
        builder.show()
    }

    /** Edits the live favourites (uninstalled ones are dropped on save) and redraws. */
    private fun updateFavourites(edit: (MutableList<String>) -> Unit) {
        val favourites = liveFavourites().map { it.component.flattenToString() }.toMutableList()
        edit(favourites)
        settings.favourites = favourites.distinct()
        renderFavourites()
    }

    // --- App drawer ---------------------------------------------------------------------

    private fun openDrawer() {
        homePage.visibility = View.GONE
        drawer.visibility = View.VISIBLE
        list.setSelection(0)
        // They tapped a search bar, so the keyboard comes up ready to type.
        search.requestFocus()
        search.post { getSystemService(InputMethodManager::class.java).showSoftInput(search, 0) }
    }

    private fun closeDrawer() {
        if (!isDrawerOpen) return
        hideKeyboard()
        search.text.clear()
        search.clearFocus()
        drawer.visibility = View.GONE
        homePage.visibility = View.VISIBLE
    }

    private fun setUpSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshDrawer()
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            drawerAdapter.firstApp()?.let(::launch)
            true
        }
    }

    /** Sectioned A–Z list when browsing; ranked results without headings while searching. */
    private fun refreshDrawer() {
        val query = search.text.toString()
        if (query.isBlank()) {
            val sections = AppSections.build(catalog.apps) { it.label }
            drawerAdapter.submitSections(sections)
            index.letters = sections.map { it.letter }
            index.visibility = View.VISIBLE
        } else {
            drawerAdapter.submitPlain(AppSearch.filter(catalog.apps, query) { it.label })
            index.visibility = View.GONE
        }
    }

    private fun launch(app: AppCatalog.App) {
        try {
            launcherApps.startMainActivity(app.component, app.user, null, null)
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
        faintInk = Color.argb(0x80, Color.red(ink), Color.green(ink), Color.blue(ink))
        ruleColor = EinkFilter.tint(0xDD, warmth)

        root.setBackgroundColor(paper)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        clock.setTextColor(ink)
        date.setTextColor(ink)
        rule.setBackgroundColor(ruleColor)
        openDrawer.setTextColor(faintInk)
        openDrawer.backgroundTintList = ColorStateList.valueOf(ruleColor)
        search.setTextColor(ink)
        search.setHintTextColor(faintInk)
        search.backgroundTintList = ColorStateList.valueOf(ruleColor)
        index.color = ink
        drawerAdapter.notifyDataSetChanged()
    }

    // --- Drawer list --------------------------------------------------------------------

    private sealed interface Row {
        class Heading(val letter: String) : Row
        class App(val app: AppCatalog.App) : Row
    }

    /** App names as plain text rows, optionally under bold letter headings. */
    private inner class DrawerAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()
        private var headingPositions: Map<String, Int> = emptyMap()

        fun submitSections(sections: List<AppSections.Section<AppCatalog.App>>) {
            val out = ArrayList<Row>()
            val positions = HashMap<String, Int>()
            for (section in sections) {
                positions[section.letter] = out.size
                out += Row.Heading(section.letter)
                section.items.mapTo(out) { Row.App(it) }
            }
            rows = out
            headingPositions = positions
            notifyDataSetChanged()
        }

        fun submitPlain(apps: List<AppCatalog.App>) {
            rows = apps.map { Row.App(it) }
            headingPositions = emptyMap()
            notifyDataSetChanged()
        }

        fun positionOf(letter: String): Int? = headingPositions[letter]

        fun firstApp(): AppCatalog.App? = rows.firstNotNullOfOrNull { (it as? Row.App)?.app }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Heading) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is Row.App

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val layout = if (row is Row.Heading) R.layout.item_home_section else R.layout.item_home_app
            val view = (convertView ?: LayoutInflater.from(parent.context).inflate(layout, parent, false)) as TextView
            view.text = when (row) {
                is Row.Heading -> row.letter
                is Row.App -> row.app.label
            }
            view.setTextColor(ink)
            return view
        }
    }
}
