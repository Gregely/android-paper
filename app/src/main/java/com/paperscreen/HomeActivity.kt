package com.paperscreen

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.util.Date
import java.util.Locale

/**
 * A minimal home screen that reads like a page of a book, in two views:
 *
 * - **Home page:** clock and date at the top, up to six favourite apps as centred text in
 *   the middle, and "Search apps" at the bottom. Long-press a favourite and drag to reorder;
 *   long-press and release to replace or remove it.
 * - **App drawer** (opened from "Search apps"): a search field, the alphabetical app list
 *   with letter headings, and an A–Z index strip. Back or Home returns to the home page.
 *
 * No icons, wallpaper, widgets, dock or folders. Colours follow the filter's warmth setting;
 * clock format, size, date, seconds and font follow the Home screen settings.
 */
class HomeActivity : Activity() {

    private lateinit var settings: PaperSettings
    private lateinit var catalog: AppCatalog
    private lateinit var launcherApps: LauncherApps
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var homePage: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var rule: View
    private lateinit var favouritesView: RecyclerView
    private lateinit var openDrawer: TextView
    private val favouritesAdapter = FavouritesAdapter()

    private lateinit var drawer: View
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var index: AlphabetStrip
    private val drawerAdapter = DrawerAdapter()

    private var ink = Color.rgb(0x33, 0x33, 0x33)
    private var faintInk = Color.argb(0x80, 0x33, 0x33, 0x33)
    private var paper = Color.WHITE
    private var ruleColor = Color.rgb(0xDD, 0xDD, 0xDD)
    private var font = HomeFont.DEFAULT
    private var showSeconds = false

    /** The replace/add picker, if open; dismissed when going home or on destroy. */
    private var picker: AlertDialog? = null

    /** A favourite is being dragged; app-list refreshes wait until it's dropped. */
    private var dragging = false
    private var refreshAfterDrag = false
    private var resumed = false

    /** Locale-aware letter buckets for the drawer (handles accents and non-Latin scripts). */
    private val sectionKeys by lazy { LocaleSectionKeys(Locale.getDefault()) }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
            // Night warmth shifts the tint through the evening and morning.
            if (settings.nightWarmth) applyColors()
        }
    }

    /** Ticks on each second boundary while seconds are shown. */
    private val secondTick = object : Runnable {
        override fun run() {
            updateClock()
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
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
        favouritesView = findViewById(R.id.home_favourites)
        openDrawer = findViewById(R.id.home_open_drawer)
        drawer = findViewById(R.id.home_drawer)
        search = findViewById(R.id.home_search)
        list = findViewById(R.id.home_apps)
        index = findViewById(R.id.home_index)

        setUpFavourites()
        openDrawer.setOnClickListener { openDrawer() }
        list.adapter = drawerAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            (drawerAdapter.getItem(position) as? Row.App)?.let { launch(it.app) }
        }
        index.onLetter = { letter -> drawerAdapter.positionOf(letter)?.let { list.setSelectionFromTop(it, 0) } }
        setUpSearch()

        catalog = AppCatalog(this) {
            refreshDrawer()
            // An uninstalled favourite disappears straight away, unless one is mid-drag.
            if (dragging) refreshAfterDrag = true else renderFavourites()
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
        // Warmth and the home screen settings may have changed while we were away. This also
        // restarts the clock's tick, which runs whenever the page is visible (even unfocused,
        // as in split screen).
        applyColors()
        applyHomeStyle()
        if (dragging) refreshAfterDrag = true else renderFavourites()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(timeReceiver)
        main.removeCallbacks(secondTick)
        // Coming back home (from an app, or the screen turning on) always shows the home page.
        closeDrawer()
        super.onStop()
    }

    override fun onDestroy() {
        picker?.dismiss()
        main.removeCallbacks(secondTick)
        catalog.stop()
        super.onDestroy()
    }

    /** The home button: back to the home page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        picker?.dismiss()
        closeDrawer()
    }

    /** Back closes the drawer; on the home page it does nothing, as on any launcher. */
    @Deprecated("Framework back handling (onBackInvokedCallback needs an opt-in).")
    override fun onBackPressed() {
        closeDrawer()
    }

    // --- Clock and style ----------------------------------------------------------------

    /** Clock format, size, date, seconds and font from the Home screen settings. */
    private fun applyHomeStyle() {
        font = settings.homeFont
        showSeconds = settings.showSeconds
        clock.textSize = settings.clockSize.sp
        date.visibility = if (settings.showDate) View.VISIBLE else View.GONE

        val regular = font.typeface()
        listOf(clock, date, openDrawer, search).forEach { it.typeface = regular }
        index.typeface = regular
        favouritesAdapter.restyle()
        drawerAdapter.notifyDataSetChanged()

        main.removeCallbacks(secondTick)
        if (showSeconds) secondTick.run() else updateClock()
    }

    private fun updateClock() {
        val locale = Locale.getDefault()
        val is24h = settings.clock24h ?: DateFormat.is24HourFormat(this)
        val skeleton = (if (is24h) "Hm" else "hm") + if (showSeconds) "s" else ""
        // Hours and minutes (and seconds) only: drop the AM/PM (or day-period) marker.
        val timePattern = DateFormat.getBestDateTimePattern(locale, skeleton)
            .replace(Regex("\\s*[aBb]+\\s*"), "")
        val now = Date()
        clock.text = DateFormat.format(timePattern, now)
        date.text = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), now)
    }

    // --- Favourites ---------------------------------------------------------------------

    private fun setUpFavourites() {
        favouritesView.layoutManager = LinearLayoutManager(this)
        favouritesView.adapter = favouritesAdapter
        // Rows slide into place when reordered; no cross-fades on rebinds.
        (favouritesView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        ItemTouchHelper(FavouriteDragCallback()).attachToRecyclerView(favouritesView)
    }

    /** Favourites that are still installed, in the user's order. */
    private fun liveFavourites(): List<AppCatalog.App> = settings.favourites.mapNotNull(catalog::find)

    private fun renderFavourites() {
        // Until the app list has loaded there's nothing to resolve the favourites against.
        favouritesAdapter.submit(if (catalog.apps.isEmpty()) null else liveFavourites())
    }

    /**
     * Chooses an app to add (when [replacing] is null) or to put in place of favourite
     * [replacing]; the latter also offers Remove.
     */
    private fun pickApp(replacing: Int?) {
        val apps = catalog.apps
        if (apps.isEmpty()) return
        picker?.dismiss()
        val adapter = DrawerAdapter().apply { submitPlain(apps) }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (replacing == null) R.string.home_add_favourite_title else R.string.home_pick_favourite)
            .setAdapter(adapter) { _, which ->
                val chosen = apps[which].component.flattenToString()
                updateFavourites { favourites ->
                    when {
                        replacing == null || replacing >= favourites.size -> favourites += chosen
                        else -> favourites[replacing] = chosen
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (replacing != null) {
            builder.setNeutralButton(R.string.home_remove_favourite) { _, _ ->
                updateFavourites { if (replacing < it.size) it.removeAt(replacing) }
            }
        }
        picker = builder.show().also { dialog ->
            // The same warmth-tinted page as the home screen, not the default dialog white.
            dialog.window?.setBackgroundDrawable(ColorDrawable(paper))
            dialog.setOnDismissListener { if (picker === dialog) picker = null }
        }
    }

    /** Edits the live favourites (uninstalled ones are dropped on save) and redraws. */
    private fun updateFavourites(edit: (MutableList<String>) -> Unit) {
        val favourites = liveFavourites().map { it.component.flattenToString() }.toMutableList()
        edit(favourites)
        settings.favourites = favourites.distinct()
        renderFavourites()
    }

    /** Favourites as centred labels, then "+ Add app" while there's room for more. */
    private inner class FavouritesAdapter : RecyclerView.Adapter<FavouritesAdapter.Holder>() {
        val apps = ArrayList<AppCatalog.App>()
        private var showAdd = false

        inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

        @SuppressLint("NotifyDataSetChanged") // The whole list is replaced; at most seven rows.
        fun submit(favourites: List<AppCatalog.App>?) {
            apps.clear()
            favourites?.let(apps::addAll)
            showAdd = favourites != null && favourites.size < Favourites.MAX
            notifyDataSetChanged()
        }

        /** Rebinds every row in place after a font or colour change. */
        fun restyle() = notifyItemRangeChanged(0, itemCount)

        /**
         * Moves a favourite while it's being dragged; saved when the drag ends. A fast drag can
         * jump several rows at once, so this shifts the rows in between (as the animation
         * shows) rather than swapping two.
         */
        fun move(from: Int, to: Int) {
            apps.add(to, apps.removeAt(from))
            notifyItemMoved(from, to)
        }

        fun isAddRow(position: Int) = position == apps.size

        override fun getItemCount() = apps.size + if (showAdd) 1 else 0
        override fun getItemViewType(position: Int) = if (isAddRow(position)) TYPE_ADD else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_home_favourite, parent, false) as TextView,
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val label = holder.label
            label.typeface = font.typeface()
            if (isAddRow(position)) {
                label.text = getString(R.string.home_add_favourite)
                label.textSize = 16f
                label.setTextColor(faintInk)
                label.setOnClickListener { pickApp(replacing = null) }
            } else {
                val app = apps[position]
                label.text = app.label
                label.textSize = 22f
                label.setTextColor(ink)
                label.setOnClickListener { launch(app) }
            }
        }
    }

    /**
     * Long-press picks a favourite up; dragging moves it and the others slide apart. The new
     * order is saved on release. Releasing without moving opens replace/remove instead.
     */
    private inner class FavouriteDragCallback : ItemTouchHelper.Callback() {
        private var moved = false

        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (favouritesAdapter.isAddRow(viewHolder.bindingAdapterPosition)) {
                0
            } else {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (favouritesAdapter.isAddRow(to)) return false // "+ Add app" stays last.
            favouritesAdapter.move(from, to)
            moved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                moved = false
                dragging = true
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            viewHolder.itemView.translationX = 0f
            viewHolder.itemView.translationY = 0f
            dragging = false
            if (moved) {
                settings.favourites = favouritesAdapter.apps.map { it.component.flattenToString() }
            } else if (resumed) {
                // A long-press released in place (not one interrupted by leaving the screen).
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && !favouritesAdapter.isAddRow(position)) {
                    pickApp(replacing = position)
                }
            }
            moved = false
            if (refreshAfterDrag) {
                refreshAfterDrag = false
                recyclerView.post { renderFavourites() }
            }
        }

        /** Just follow the finger: no lift, shadow or scaling. */
        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            viewHolder.itemView.translationX = dX
            viewHolder.itemView.translationY = dY
        }
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
            val sections = AppSections.build(catalog.apps, { it.label }, sectionKeys::key)
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
            // Back to the home page (search cleared) even if the app doesn't fully cover us,
            // as with translucent or floating apps, where onStop never comes.
            closeDrawer()
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
        val warmth = settings.effectiveWarmth()
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
        favouritesAdapter.restyle()
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
            when (row) {
                is Row.Heading -> {
                    view.text = row.letter
                    view.typeface = font.typeface(Typeface.BOLD)
                }
                is Row.App -> {
                    view.text = row.app.label
                    view.typeface = font.typeface()
                }
            }
            view.setTextColor(ink)
            return view
        }
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_ADD = 1
    }
}
