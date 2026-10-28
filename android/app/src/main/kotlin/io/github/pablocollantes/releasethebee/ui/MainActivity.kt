package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.api.EntryPage
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.sink.EntryPush
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * The recordings list. The RTB's own button mutes and unmutes, so this screen is about
 * what was recorded: a status header (tap it for Settings) and the entries, newest first.
 *
 * It never polls. It fetches when it opens, after a push from the receiver, and when the
 * receiver connection comes back (pushes may have been missed while it was down). After
 * the first full fetch, every fetch is `?since=`, which returns only what changed.
 */
class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var fmt: Format
    private lateinit var settings: Settings
    private val index get() = Receiver.index
    private val main = Handler(Looper.getMainLooper())

    private lateinit var rtbDot: View
    private lateinit var rtbText: TextView
    private lateinit var rtbSince: TextView
    private lateinit var receiverDot: View
    private lateinit var receiverText: TextView
    private lateinit var statusDetail: TextView

    private lateinit var banner: TextView
    private lateinit var list: ListView
    private val adapter = EntriesAdapter()
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: Button
    private lateinit var progress: ProgressBar

    private var source: Receiver.Source? = null
    private var problem: Receiver.Problem? = null
    private var fetchedOk = false
    private var loading = false
    private var again = false
    private var lastNetwork: String? = null

    private val refreshSoon = Runnable { refresh() }

    private val onStatus: () -> Unit = {
        renderStatus()
        // The receiver connection came back: catch up on pushes it may have missed.
        val network = AppStatus.snapshot.sinks["network"]
        if (network == "connected" && lastNetwork != null && lastNetwork != "connected") scheduleRefresh()
        lastNetwork = network
    }

    private val onEntry: (EntryPush) -> Unit = { p ->
        val known = index.applyPush(p)
        if (known) adapter.update()
        // A push carries status and title only. Fetch the rest (duration, preview) when the
        // entry is new, or has just ended or finished.
        val status = Entry.Status.of(p.status)
        if (!known || status == Entry.Status.QUEUED || status.finished) scheduleRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(this))
        settings = Settings(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        AppStatus.addListener(onStatus)
        AppStatus.addEntryListener(onEntry)
        lastNetwork = AppStatus.snapshot.sinks["network"]
        renderStatus()
        adapter.update()
        render()
        refresh()
    }

    override fun onStop() {
        AppStatus.removeListener(onStatus)
        AppStatus.removeEntryListener(onEntry)
        main.removeCallbacks(refreshSoon)
        super.onStop()
    }

    // ------------------------------------------------------------------ data

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshSoon)
        main.postDelayed(refreshSoon, 800)      // one fetch for a burst of pushes
    }

    private fun refresh() {
        if (loading) {
            again = true
            return
        }
        loading = true
        render()
        val app = applicationContext
        val origin = Receiver.indexOrigin
        val cursor = index.cursor
        Ui.io.execute {
            val src = Receiver.source(app)
            val ready = src as? Receiver.Source.Ready
            val since = if (ready != null && ready.api.origin == origin) cursor else null
            val result = ready?.let { runCatching { it.api.entries(since) } }
            runOnUiThread { onFetched(src, since, result) }
        }
    }

    private fun onFetched(src: Receiver.Source, since: Long?, result: Result<EntryPage>?) {
        if (isDestroyed) return
        loading = false
        source = src
        when {
            result == null -> problem = Receiver.problem(src)
            result.isSuccess -> {
                val page = result.getOrThrow()
                if (since == null) {
                    index.replaceAll(page)
                    Receiver.indexOrigin = (src as Receiver.Source.Ready).api.origin
                } else {
                    index.merge(page)
                }
                problem = null
                fetchedOk = true
            }
            else -> {
                problem = Receiver.problem(src, result.exceptionOrNull())
                fetchedOk = false
            }
        }
        adapter.update()
        render()
        renderStatus()
        if (again) {
            again = false
            refresh()
        }
    }

    // ------------------------------------------------------------------ render

    /** The list, or what to show instead of it. */
    private fun render() {
        val src = source
        val showList = src !is Receiver.Source.NotSet && src !is Receiver.Source.TcpOnly && adapter.count > 0
        list.visibility = if (showList) View.VISIBLE else View.GONE
        val p = problem
        banner.visibility = if (showList && p != null) View.VISIBLE else View.GONE
        if (showList && p != null) banner.text = "${p.title}. Showing the last list it sent."

        progress.visibility = if (!showList && loading && p == null) View.VISIBLE else View.GONE
        empty.visibility = if (!showList && progress.visibility != View.VISIBLE && src != null) View.VISIBLE else View.GONE
        if (p != null) {
            emptyTitle.text = p.title
            emptyBody.text = p.body
            emptyAction.visibility = View.VISIBLE
            emptyAction.text = if (p.action == Receiver.Problem.Action.SETTINGS) "Open Settings" else "Try again"
            emptyAction.setOnClickListener {
                if (p.action == Receiver.Problem.Action.SETTINGS) openSettings() else refresh()
            }
        } else {
            emptyTitle.text = "No recordings yet"
            emptyBody.text = if (AppStatus.snapshot.serviceRunning) "Unmute your RTB to record."
            else "Unmute your RTB to record. The connection is off: start it in Settings."
            emptyAction.visibility = if (AppStatus.snapshot.serviceRunning) View.GONE else View.VISIBLE
            emptyAction.text = "Open Settings"
            emptyAction.setOnClickListener { openSettings() }
        }
    }

    private fun renderStatus() {
        val st = AppStatus.snapshot
        val (rtb, rtbColor) = when (st.link) {
            RtbLink.State.STREAMING -> "RTB recording" to ui.red
            RtbLink.State.MUTED -> "RTB muted" to ui.gray
            RtbLink.State.PAUSED -> "RTB muted from Settings" to ui.gray
            RtbLink.State.SCANNING, RtbLink.State.CONNECTING, RtbLink.State.HANDSHAKE,
            RtbLink.State.SEALING, RtbLink.State.WAITING -> "Connecting to RTB" to ui.accent
            RtbLink.State.FAILED -> "RTB needs attention" to ui.red
            RtbLink.State.STOPPED -> if (st.serviceRunning) "Connecting to RTB" to ui.accent else "RTB off" to ui.gray
        }
        rtbText.text = rtb
        tint(rtbDot, rtbColor)
        val started = st.sessionStartedAt
        rtbSince.text = if (st.link == RtbLink.State.STREAMING && started != null) "since ${fmt.clock(started)}" else ""

        val (receiver, receiverColor) = receiverState(st)
        receiverText.text = receiver
        tint(receiverDot, receiverColor)

        val detail = when (st.link) {
            RtbLink.State.FAILED -> st.linkDetail
            RtbLink.State.WAITING -> st.linkDetail.replaceFirstChar { it.uppercase() }
            RtbLink.State.STOPPED -> if (st.serviceRunning) "" else "Turned off. Start it in Settings."
            else -> ""
        }
        statusDetail.text = detail
        statusDetail.setTextColor(if (st.link == RtbLink.State.FAILED) ui.red else ui.fgMuted)
        statusDetail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }

    private fun receiverState(st: AppStatus.Snapshot): Pair<String, Int> {
        val wsSet = settings.sinkKind == Settings.SinkKind.WEBSOCKET && settings.wsUrl.isNotBlank()
        val tcpSet = settings.sinkKind == Settings.SinkKind.TCP && settings.tcpHost.isNotBlank()
        if (!wsSet && !tcpSet) return "No receiver set" to ui.gray
        val network = st.sinks["network"]
        if (st.serviceRunning && network != null) {
            return when {
                network == "connected" -> "Receiver connected" to ui.green
                network == "connecting" -> "Connecting to the receiver" to ui.accent
                network.startsWith("unreachable") -> "Receiver unreachable" to ui.red
                else -> "Receiver $network" to ui.gray
            }
        }
        // Not streaming: say what the last fetch found.
        return when {
            fetchedOk -> "Receiver reachable" to ui.green
            problem != null && source is Receiver.Source.Ready -> "Receiver unreachable" to ui.red
            else -> "Receiver" to ui.gray
        }
    }

    private fun tint(dot: View, color: Int) = ui.setDot(dot, color)

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val (bar, _) = ui.topBar(
            "Recordings", null,
            ui.icon(R.drawable.ic_refresh, "Refresh") { refresh() },
            ui.icon(R.drawable.ic_settings, "Settings") { openSettings() },
        )
        root.addView(bar)

        // Status header: tap for Settings.
        rtbDot = ui.dot(ui.gray, 10)
        rtbText = ui.text(16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        rtbSince = ui.text(13f, ui.fgMuted)
        receiverDot = ui.dot(ui.gray, 10)
        receiverText = ui.text(14f, ui.fgMuted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusDetail = ui.text(13f, ui.fgMuted).apply { visibility = View.GONE }
        val lines = ui.column(
            ui.row(rtbDot, rtbText.withStart(10, weight = 1f), rtbSince.withStart(8)),
            ui.row(receiverDot, receiverText.withStart(10, weight = 1f)).apply { setPadding(0, ui.dp(6), 0, 0) },
            statusDetail.apply { setPadding(ui.dp(20), ui.dp(6), 0, 0) },
        )
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = ColorStateList.valueOf(ui.fgMuted)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.rounded(ui.surface, 16))
            setPadding(ui.dp(16), ui.dp(14), ui.dp(8), ui.dp(14))
            isClickable = true
            contentDescription = "Status. Opens Settings"
            setOnClickListener { openSettings() }
            addView(lines, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(8) })
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
        })

        banner = ui.text(13f, ui.fg).apply {
            background = ui.ripple(ui.rounded(ui.redSoft, 12))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            visibility = View.GONE
            setOnClickListener { refresh() }
        }
        root.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(4))
        })

        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ ->
                (this@MainActivity.adapter.getItem(pos) as? Row.Item)?.let { EntryActivity.open(this@MainActivity, it.e.id) }
            }
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ui.accent)
            visibility = View.GONE
        }
        empty = buildEmpty()
        val body = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val capped = CappedWidth(this, ui.dp(760)).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        Ui.edgeToEdge(this, capped) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            list.setPadding(0, 0, 0, bottom + ui.dp(8))
            empty.setPadding(ui.dp(32), 0, ui.dp(32), bottom)
        }
        return capped
    }

    private fun buildEmpty(): LinearLayout {
        val badge = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat_rtb)
            imageTintList = ColorStateList.valueOf(ui.onAccent)
            background = ui.rounded(ui.accent, 36)
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
        }
        emptyTitle = ui.text(19f, bold = true).apply { gravity = Gravity.CENTER }
        emptyBody = ui.text(15f, ui.fgMuted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(ui.dpf(2f), 1f)
        }
        emptyAction = ui.primaryButton("Open Settings") { openSettings() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(badge, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
            addView(emptyTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(20) })
            addView(emptyBody, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(8) })
            addView(emptyAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(20) })
        }
    }

    private fun View.withStart(marginDp: Int, weight: Float = 0f): View = apply {
        layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else WRAP_CONTENT, WRAP_CONTENT, weight).apply {
            marginStart = ui.dp(marginDp)
        }
    }

    // ------------------------------------------------------------------ list

    private sealed interface Row {
        class Day(val label: String) : Row
        class Item(val e: Entry) : Row
    }

    private inner class EntriesAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()

        /** Rebuild from [index], with a header for each day. */
        fun update() {
            val out = ArrayList<Row>()
            var day: java.time.LocalDate? = null
            val now = System.currentTimeMillis()
            for (e in index.entries) {
                val d = fmt.localDate(e.startedAt)
                if (d != day) {
                    out += Row.Day(fmt.day(e.startedAt, now))
                    day = d
                }
                out += Row.Item(e)
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Day) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val r = rows[position]) {
                is Row.Day -> ((convertView as? TextView) ?: ui.text(13f, ui.fgMuted, bold = true).apply {
                    setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(4))
                }).apply { text = r.label }
                is Row.Item -> {
                    val holder = (convertView?.tag as? EntryRow) ?: EntryRow()
                    holder.bind(r.e)
                    holder.view
                }
            }
    }

    /** One recording in the list. */
    private inner class EntryRow {
        val title = ui.text(16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val duration = ui.text(13f, ui.fgMuted)
        val time = ui.text(13f, ui.fgMuted)
        val kind = ui.badge("Summary", ui.surface, ui.fgMuted)
        val statusDot = ui.dot(ui.gray, 6)
        val status = ui.text(13f, ui.fgMuted)
        val preview = ui.text(14f, ui.fgMuted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(ui.dpf(1f), 1f)
        }
        val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(ui.row(title.also { it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }, duration.withStart(12)))
            addView(
                ui.row(time.withStart(0), kind.withStart(0), statusDot.apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)).apply { marginStart = ui.dp(10) }
                }, status.withStart(6)).apply { setPadding(0, ui.dp(6), 0, 0) },
            )
            addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            tag = this@EntryRow
        }

        fun bind(e: Entry) {
            title.text = fmt.title(e)
            duration.text = e.durationS?.takeIf { e.endedAt != null }?.let { Format.shortDuration(it) } ?: ""
            // The fallback title already says the time.
            time.visibility = if (fmt.hasRealTitle(e)) View.VISIBLE else View.GONE
            time.text = fmt.clock(e.startedAt)
            (time.layoutParams as LinearLayout.LayoutParams).marginEnd = ui.dp(10)
            kind.text = fmt.kind(e).uppercase()
            if (e.kind == Entry.Kind.COMMAND) {
                kind.background = ui.rounded(ui.accent, 6)
                kind.setTextColor(ui.onAccent)
            } else {
                kind.background = ui.rounded(ui.surface, 6)
                kind.setTextColor(ui.fgMuted)
            }
            status.text = fmt.status(e)
            val c = statusColor(e.status)
            status.setTextColor(if (e.status == Entry.Status.DONE) ui.fgMuted else c)
            tint(statusDot, c)
            preview.text = e.preview ?: e.error?.let { "Failed: $it" } ?: ""
            preview.visibility = if (preview.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun statusColor(s: Entry.Status) = when (s) {
        Entry.Status.RECORDING, Entry.Status.FAILED -> ui.red
        Entry.Status.DONE -> ui.green
        else -> ui.accentText
    }
}
