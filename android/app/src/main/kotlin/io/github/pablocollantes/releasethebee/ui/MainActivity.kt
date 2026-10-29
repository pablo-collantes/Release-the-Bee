package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * The app: three tabs under one bar, switched from the bottom.
 *
 * - **Home**: the RTB and receiver at a glance, reminders coming up, the way to Notes, and
 *   the latest summaries.
 * - **Summaries**: every recording, newest first. Long press selects; then delete.
 * - **Notes**: what you said into each note, by title.
 * - **To-do**: reminders as a checklist.
 *
 * It never polls. [Library] fetches when the app opens and when the receiver pushes a
 * change, and the service keeps it current in between.
 */
class MainActivity : Activity() {

    enum class Tab(val title: String, val label: String, val icon: Int) {
        HOME("Release the Bee", "Home", R.drawable.ic_home),
        SUMMARIES("Summaries", "Summaries", R.drawable.ic_summaries),
        NOTES("Notes", "Notes", R.drawable.ic_notes),
        TODO("To-do", "To-do", R.drawable.ic_todo),
    }

    lateinit var ui: Ui
        private set
    lateinit var fmt: Format
        private set
    lateinit var settings: Settings
        private set
    lateinit var reminders: ReminderViews
        private set

    private lateinit var home: HomeTab
    private lateinit var summaries: SummariesTab
    private lateinit var notes: NotesTab
    private lateinit var todo: TodoTab
    private var tab = Tab.HOME

    private lateinit var titleView: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selectTitle: TextView
    private val navItems = HashMap<Tab, Pair<FrameLayout, TextView>>()
    private val pages = HashMap<Tab, View>()
    private var lastLive: List<String> = emptyList()

    private val onLibrary: () -> Unit = { renderAll() }

    private val onStatus: () -> Unit = {
        home.renderStatus()
        val live = AppStatus.snapshot.live
        if (live != lastLive) {
            lastLive = live
            home.render()
            summaries.render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(this))
        settings = Settings(this)
        reminders = ReminderViews(this, ui, fmt)
        Library.load(this)
        home = HomeTab(this)
        summaries = SummariesTab(this)
        notes = NotesTab(this)
        todo = TodoTab(this)
        setContentView(buildUi())
        show(tabOf(intent) ?: (savedInstanceState?.getString(KEY_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.HOME))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabOf(intent)?.let { show(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    override fun onStart() {
        super.onStart()
        AppStatus.addListener(onStatus)
        Library.addListener(onLibrary)
        lastLive = AppStatus.snapshot.live
        renderAll()
        home.renderStatus()
        Library.refresh(this)
    }

    override fun onStop() {
        AppStatus.removeListener(onStatus)
        Library.removeListener(onLibrary)
        super.onStop()
    }

    @Deprecated("Predictive back isn't enabled; this is what back does here")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            summaries.selecting -> summaries.clearSelection()
            tab != Tab.HOME -> show(Tab.HOME)
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ navigation

    fun show(t: Tab) {
        if (t != Tab.SUMMARIES) summaries.clearSelection()
        tab = t
        titleView.text = t.title
        pages.forEach { (k, v) -> v.visibility = if (k == t) View.VISIBLE else View.GONE }
        navItems.forEach { (k, item) ->
            val (pill, label) = item
            val on = k == t
            pill.background = if (on) ui.rounded(ui.accentSoft, 16) else null
            (pill.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) ui.fg else ui.fgMuted)
            label.setTextColor(if (on) ui.fg else ui.fgMuted)
            label.typeface = if (on) ui.medium else ui.regular
        }
    }

    fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    fun openNotes() = show(Tab.NOTES)

    /** The selection in Summaries changed: swap the bar for the selection's own. */
    fun selectionChanged(count: Int) {
        normalBar.visibility = if (count == 0) View.VISIBLE else View.GONE
        selectBar.visibility = if (count == 0) View.GONE else View.VISIBLE
        selectTitle.text = "$count selected"
    }

    private fun renderAll() {
        home.render()
        summaries.render()
        notes.render()
        todo.render()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val (bar, t) = ui.topBar(
            Tab.HOME.title, null,
            ui.icon(R.drawable.ic_refresh, "Refresh") { Library.refresh(this) },
            ui.icon(R.drawable.ic_settings, "Settings") { openSettings() },
        )
        normalBar = bar
        titleView = t
        selectTitle = ui.title("")
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
            visibility = View.GONE
            addView(ui.icon(R.drawable.ic_close, "Cancel selection") { summaries.clearSelection() })
            addView(selectTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4) })
            addView(ui.icon(R.drawable.ic_delete, "Delete selected") { summaries.confirmDelete() })
        }

        val content = FrameLayout(this)
        pages[Tab.HOME] = home.view
        pages[Tab.SUMMARIES] = summaries.view
        pages[Tab.NOTES] = notes.view
        pages[Tab.TODO] = todo.view
        pages.values.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }

        val nav = buildNav()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(normalBar)
            addView(selectBar)
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(ui.divider) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(nav)
        }
        val capped = CappedWidth(this, ui.dp(760)).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        Ui.edgeToEdge(this, capped) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            nav.setPadding(0, ui.dp(10), 0, ui.dp(12) + bottom)
        }
        return capped
    }

    /** Material-style bottom navigation: an icon in a pill that fills when selected, and a label. */
    private fun buildNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.background)
        }
        for (t in Tab.entries) {
            val icon = ImageView(this).apply { setImageResource(t.icon) }
            val pill = FrameLayout(this).apply {
                addView(icon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            }
            val label = ui.text(12f, value = t.label)
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.ripple(radiusDp = 16)
                isClickable = true
                contentDescription = t.label
                setOnClickListener { show(t) }
                addView(pill, LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)))
                addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(4) })
            }
            navItems[t] = pill to label
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return nav
    }

    companion object {
        private const val EXTRA_TAB = "tab"
        private const val KEY_TAB = "tab"

        /** Open the app on [tab] (from a reminder's notification). */
        fun intent(ctx: Context, tab: Tab): Intent = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_TAB, tab.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun tabOf(i: Intent?): Tab? = i?.getStringExtra(EXTRA_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
    }
}
