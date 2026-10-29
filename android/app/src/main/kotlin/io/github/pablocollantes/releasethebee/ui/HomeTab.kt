package io.github.pablocollantes.releasethebee.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Notes
import io.github.pablocollantes.releasethebee.data.Reminders
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * Home: the RTB and the receiver at a glance (tap for Settings), the next few reminders,
 * the way to Notes, and the latest summaries. Everything leads to its full page.
 */
class HomeTab(private val host: MainActivity) {
    private val ui = host.ui
    private val fmt = host.fmt

    private val rtbDot = ui.dot(ui.gray, 10)
    private val rtbText = ui.text(16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val rtbSince = ui.text(13f, ui.fgMuted)
    private val battery = BatteryView(host, ui)
    private val batteryText = ui.text(13f, ui.fgMuted)
    private val batteryRow = ui.row(battery, batteryText.apply { setPadding(ui.dp(6), 0, 0, 0) }).apply { visibility = View.GONE }
    private val receiverDot = ui.dot(ui.gray, 10)
    private val receiverText = ui.text(14f, ui.fgMuted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val statusDetail = ui.text(13f, ui.fgMuted).apply { visibility = View.GONE }

    private val problemCard = ui.card(ui.redSoft)
    private val problemTitle = ui.text(15f, bold = true)
    private val problemBody = ui.text(14f).apply { setLineSpacing(ui.dpf(1f), 1f) }
    private val problemAction = ui.link("") {}

    private val upcoming = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val upcomingEmpty = hint("Nothing coming up. Double press your RTB and say “Remind me to…”")
    private val notesSub = ui.text(13f, ui.fgMuted)
    private val recent = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val recentEmpty = hint("")

    val view: ScrollView = ScrollView(host).apply {
        clipToPadding = false
        addView(build(), android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun render() {
        val entries = Library.index.entries
        val now = System.currentTimeMillis()

        val p = Library.problem
        problemCard.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p != null) {
            problemTitle.text = p.title
            problemBody.text = p.body
            problemAction.text = if (p.action == Receiver.Problem.Action.SETTINGS) "Open Settings" else "Try again"
            problemAction.setOnClickListener {
                if (p.action == Receiver.Problem.Action.SETTINGS) host.openSettings() else Library.refresh(host)
            }
        }

        upcoming.removeAllViews()
        val next = Reminders.active(Reminders.of(entries)).take(3)
        next.forEach { upcoming.addView(host.reminders.row(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        upcomingEmpty.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE

        val notes = Notes.of(entries)
        notesSub.text = when (notes.size) {
            0 -> "Say “New note…” or “Add … to my list”"
            1 -> "1 note · ${fmt.day(notes[0].updatedAt, now)}"
            else -> "${notes.size} notes · last added ${fmt.day(notes[0].updatedAt, now).replaceFirstChar { it.lowercase() }}"
        }

        recent.removeAllViews()
        val live = AppStatus.snapshot.live
        val latest = entries.filter { it.kind == Entry.Kind.SUMMARY }.take(4)
        latest.forEach { e ->
            val row = EntryRow(host, ui, fmt)
            row.bind(e, live = e.id in live)
            row.view.background = ui.ripple()
            row.view.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
            row.view.setOnClickListener { EntryActivity.open(host, e.id) }
            recent.addView(row.view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        recentEmpty.text = if (AppStatus.snapshot.serviceRunning) "No summaries yet. Unmute your RTB to record one."
        else "No summaries yet. The connection is off: start it in Settings."
        recentEmpty.visibility = if (latest.isEmpty()) View.VISIBLE else View.GONE
        renderStatus()
    }

    fun renderStatus() {
        val st = AppStatus.snapshot
        val (rtb, rtbColor) = when (st.link) {
            RtbLink.State.STREAMING -> (if (st.commandOpen) "Recording a command" else "RTB recording") to ui.red
            RtbLink.State.MUTED -> "RTB muted" to ui.gray
            RtbLink.State.PAUSED -> "RTB muted from Settings" to ui.gray
            RtbLink.State.SCANNING, RtbLink.State.CONNECTING, RtbLink.State.HANDSHAKE,
            RtbLink.State.SEALING, RtbLink.State.WAITING -> "Connecting to RTB" to ui.accent
            RtbLink.State.FAILED -> "RTB needs attention" to ui.red
            RtbLink.State.STOPPED -> if (st.serviceRunning) "Connecting to RTB" to ui.accent else "RTB off" to ui.gray
        }
        rtbText.text = rtb
        ui.setDot(rtbDot, rtbColor)
        val started = st.sessionStartedAt
        rtbSince.text = if (st.link == RtbLink.State.STREAMING && started != null) "since ${fmt.clock(started)}" else ""
        renderBattery(st)

        val (receiver, receiverColor) = receiverState(st)
        receiverText.text = receiver
        ui.setDot(receiverDot, receiverColor)

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

    /** The RTB's battery, as it last said. Out of reach, that's a while ago: say when. */
    private fun renderBattery(st: AppStatus.Snapshot) {
        val level = st.battery
        batteryRow.visibility = if (level == null) View.GONE else View.VISIBLE
        if (level == null) return
        val charging = st.charging == true
        battery.set(level, charging)
        val connected = st.link in listOf(RtbLink.State.STREAMING, RtbLink.State.MUTED, RtbLink.State.PAUSED)
        val at = st.batteryAt
        batteryText.text = when {
            charging -> "$level% · charging"
            !connected && at != null -> "$level% at ${fmt.clock(at)}"
            else -> "$level%"
        }
        batteryText.setTextColor(if (level <= 15 && !charging) ui.red else ui.fgMuted)
    }

    private fun receiverState(st: AppStatus.Snapshot): Pair<String, Int> {
        val s = host.settings
        val wsSet = s.sinkKind == Settings.SinkKind.WEBSOCKET && s.wsUrl.isNotBlank()
        val tcpSet = s.sinkKind == Settings.SinkKind.TCP && s.tcpHost.isNotBlank()
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
            Library.fetchedOk -> "Receiver reachable" to ui.green
            Library.problem != null && Library.source is Receiver.Source.Ready -> "Receiver unreachable" to ui.red
            else -> "Receiver" to ui.gray
        }
    }

    // ------------------------------------------------------------------ layout

    private fun build(): View {
        val page = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24))
        }

        // Status: tap for Settings.
        val lines = ui.column(
            ui.row(rtbDot, rtbText.withStart(10, weight = 1f), rtbSince.withStart(8), batteryRow.withStart(10)),
            ui.row(receiverDot, receiverText.withStart(10, weight = 1f)).apply { setPadding(0, ui.dp(6), 0, 0) },
            statusDetail.apply { setPadding(ui.dp(20), ui.dp(6), 0, 0) },
        )
        page.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.rounded(ui.surface, 16))
            setPadding(ui.dp(16), ui.dp(14), ui.dp(8), ui.dp(14))
            isClickable = true
            contentDescription = "Status. Opens Settings"
            setOnClickListener { host.openSettings() }
            addView(lines, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chevron(), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(8) })
        }, full())

        problemCard.addView(problemTitle)
        problemCard.addView(problemBody, full(top = 4))
        problemCard.addView(problemAction.apply { setPadding(0, ui.dp(10), ui.dp(8), 0) })
        problemCard.visibility = View.GONE
        page.addView(problemCard, full(top = 12))

        page.addView(ui.sectionRow("Coming up", "All to-dos") { host.show(MainActivity.Tab.TODO) })
        page.addView(upcoming, full())
        page.addView(upcomingEmpty, full())

        // Notes: its own page.
        val notesIcon = ImageView(host).apply {
            setImageResource(R.drawable.ic_notes)
            imageTintList = ColorStateList.valueOf(ui.onAccent)
            background = ui.rounded(ui.accent, 12)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        page.addView(LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.rounded(ui.surface, 16))
            setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(14))
            isClickable = true
            contentDescription = "Notes"
            setOnClickListener { host.openNotes() }
            addView(notesIcon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            addView(ui.column(ui.text(16f, bold = true, value = "Notes"), notesSub), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(14)
            })
            addView(chevron(), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        }, full(top = 20))

        page.addView(ui.sectionRow("Recent summaries", "All summaries") { host.show(MainActivity.Tab.SUMMARIES) })
        page.addView(recent, full())
        page.addView(recentEmpty, full())
        return page
    }

    private fun hint(value: String) = ui.text(14f, ui.fgMuted, value = value).apply {
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
        setLineSpacing(ui.dpf(1f), 1f)
    }

    private fun chevron() = ImageView(host).apply {
        setImageResource(R.drawable.ic_chevron_right)
        imageTintList = ColorStateList.valueOf(ui.fgMuted)
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    private fun View.withStart(marginDp: Int, weight: Float = 0f): View = apply {
        layoutParams = LinearLayout.LayoutParams(if (weight > 0f) 0 else WRAP_CONTENT, WRAP_CONTENT, weight).apply {
            marginStart = ui.dp(marginDp)
        }
    }
}
