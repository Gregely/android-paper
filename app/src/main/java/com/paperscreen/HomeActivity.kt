package com.paperscreen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * - **Home page:** clock and date at the top, up to ten favourite apps as centred text in
 *   the middle (scrolling there if they don't fit), and "Search apps" at the bottom.
 *   Long-press a favourite and drag to reorder; long-press and release for a menu to remove
 *   it or move it to the top or bottom.
 * - **App drawer** (opened from "Search apps"): a search field, the alphabetical app list
 *   with letter headings, and an A–Z index strip. Favourites are marked with a small dot.
 *   Long-press an app to add it to or remove it from the favourites, or to open its app info.
 *   Back or Home returns to the home page.
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
    private lateinit var favouritesEmpty: TextView
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

    /** The long-press menu (drawer or home page); closed when leaving or going home. */
    private val menu by lazy { PaperMenu(this) }

    /** Stored favourites, for marking them in the drawer. */
    private var favouriteComponents: Set<ComponentName> = emptySet()

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
        favouritesEmpty = findViewById(R.id.home_favourites_empty)
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
        list.setOnItemLongClickListener { _, view, position, _ ->
            val app = (drawerAdapter.getItem(position) as? Row.App)?.app ?: return@setOnItemLongClickListener false
            showDrawerMenu(view, app)
            true
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
        menu.dismiss()
        unregisterReceiver(timeReceiver)
        main.removeCallbacks(secondTick)
        // Coming back home (from an app, or the screen turning on) always shows the home page.
        closeDrawer()
        super.onStop()
    }

    override fun onDestroy() {
        menu.dismiss()
        main.removeCallbacks(secondTick)
        catalog.stop()
        super.onDestroy()
    }

    /** The home button: back to the home page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        menu.dismiss()
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
        listOf(clock, date, openDrawer, search, favouritesEmpty).forEach { it.typeface = regular }
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
        favouritesChanged()
    }

    /** After any change to the favourites: the drawer's marks and the empty-page hint. */
    private fun favouritesChanged() {
        favouriteComponents = settings.favourites.mapNotNull(ComponentName::unflattenFromString).toSet()
        drawerAdapter.notifyDataSetChanged()
        val empty = catalog.apps.isNotEmpty() && favouritesAdapter.apps.isEmpty()
        favouritesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /** Edits the live favourites (uninstalled ones are dropped on save) and redraws. */
    private fun updateFavourites(edit: (List<String>) -> List<String>) {
        val favourites = liveFavourites().map { it.component.flattenToString() }
        settings.favourites = edit(favourites).distinct()
        renderFavourites()
    }

    /** Saves the home page's current order (after a drag or a move from its menu). */
    private fun saveFavouriteOrder() {
        settings.favourites = favouritesAdapter.apps.map { it.component.flattenToString() }
        favouritesChanged()
    }

    private fun menuColours() = PaperMenu.Colours(paper, ink, ruleColor, font.typeface())

    /** Drawer long-press: add to or remove from favourites, and app info. */
    private fun showDrawerMenu(anchor: View, app: AppCatalog.App) {
        val key = app.component.flattenToString()
        val favourites = liveFavourites().map { it.component.flattenToString() }
        val items = buildList {
            if (key in favourites) {
                add(PaperMenu.Item(getString(R.string.menu_remove_favourite)) { updateFavourites { Favourites.remove(it, key) } })
            } else if (Favourites.canAdd(favourites, key)) {
                add(PaperMenu.Item(getString(R.string.menu_add_favourite)) { updateFavourites { Favourites.add(it, key) } })
            }
            add(PaperMenu.Item(getString(R.string.menu_app_info)) { openAppInfo(app) })
        }
        menu.show(anchor, items, menuColours(), PaperMenu.Align.TEXT_START)
    }

    /** Home page long-press released in place: remove, or move to the top or bottom. */
    private fun showFavouriteMenu(anchor: View, app: AppCatalog.App) {
        val apps = favouritesAdapter.apps
        val position = favouritesAdapter.indexOf(app)
        if (position < 0) return
        val items = buildList {
            add(PaperMenu.Item(getString(R.string.menu_remove_favourite)) { favouritesAdapter.remove(app) })
            // Moves that wouldn't change anything aren't offered.
            if (position > 0) {
                add(PaperMenu.Item(getString(R.string.menu_move_top)) { favouritesAdapter.moveTo(app, 0) })
            }
            if (position < apps.lastIndex) {
                add(PaperMenu.Item(getString(R.string.menu_move_bottom)) { favouritesAdapter.moveTo(app, Int.MAX_VALUE) })
            }
        }
        menu.show(anchor, items, menuColours(), PaperMenu.Align.CENTRE)
    }

    private fun openAppInfo(app: AppCatalog.App) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: RuntimeException) {
            Toast.makeText(this, getString(R.string.home_app_info_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    /** Favourites as centred labels. */
    private inner class FavouritesAdapter : RecyclerView.Adapter<FavouritesAdapter.Holder>() {
        val apps = ArrayList<AppCatalog.App>()

        inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

        @SuppressLint("NotifyDataSetChanged") // The whole list is replaced; at most ten rows.
        fun submit(favourites: List<AppCatalog.App>?) {
            apps.clear()
            favourites?.let(apps::addAll)
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

        /** Where [app] is now, by component: the list may have been reloaded since. */
        fun indexOf(app: AppCatalog.App) = apps.indexOfFirst { it.component == app.component }

        /** From the menu: moves [app] to [to] (clamped to the list), animated, and saves. */
        fun moveTo(app: AppCatalog.App, to: Int) {
            val from = indexOf(app)
            if (from < 0) return
            val target = to.coerceIn(0, apps.lastIndex)
            if (from == target) return
            move(from, target)
            favouritesView.scrollToPosition(target)
            saveFavouriteOrder()
        }

        /** From the menu: removes [app], animated, and saves. */
        fun remove(app: AppCatalog.App) {
            val position = indexOf(app)
            if (position < 0) return
            apps.removeAt(position)
            notifyItemRemoved(position)
            saveFavouriteOrder()
        }

        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_home_favourite, parent, false) as TextView,
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val label = holder.label
            val app = apps[position]
            label.typeface = font.typeface()
            label.text = app.label
            label.setTextColor(ink)
            label.setOnClickListener { launch(app) }
        }
    }

    /**
     * Long-press picks a favourite up; dragging moves it and the others slide apart. The new
     * order is saved on release. Releasing without moving opens the favourite's menu instead.
     */
    private inner class FavouriteDragCallback : ItemTouchHelper.Callback() {
        private var moved = false

        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
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
                saveFavouriteOrder()
            } else if (resumed) {
                // A long-press released in place (not one interrupted by leaving the screen).
                favouritesAdapter.apps.getOrNull(viewHolder.bindingAdapterPosition)?.let { app ->
                    showFavouriteMenu(viewHolder.itemView, app)
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
        menu.dismiss()
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
        favouritesEmpty.setTextColor(faintInk)
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
                    val favourite = row.app.component in favouriteComponents
                    markFavourite(view, favourite)
                    view.contentDescription =
                        if (favourite) getString(R.string.home_favourite_description, row.app.label) else null
                }
            }
            view.setTextColor(ink)
            return view
        }
    }

    /**
     * A small faint dot in the row's start gutter marks a favourite. The name stays where it
     * is: the dot and its gap take the place of that much start padding.
     */
    private fun markFavourite(view: TextView, favourite: Boolean) {
        val density = resources.displayMetrics.density
        val gutter = (GUTTER_DP * density).toInt()
        val dot = if (favourite) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(faintInk)
                val size = (DOT_DP * density).toInt()
                setSize(size, size)
                setBounds(0, 0, size, size)
            }
        } else {
            null
        }
        val gap = (DOT_GAP_DP * density).toInt()
        view.compoundDrawablePadding = gap
        view.setCompoundDrawablesRelative(dot, null, null, null)
        val start = if (dot == null) gutter else gutter - dot.bounds.width() - gap
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
    }

    private companion object {
        const val DOT_DP = 5
        const val DOT_GAP_DP = 6
        const val GUTTER_DP = 16
    }
}
