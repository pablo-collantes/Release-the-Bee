package io.github.pablocollantes.releasethebee.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.service.AppStatus

/**
 * Every recording, newest first, under a header per day: summaries and commands, or one
 * kind. A long press starts a selection (the bar turns into its own, with Delete); taps
 * then add and remove. The one recording now can be deleted too: it stops, and the
 * receiver throws it away instead of transcribing it.
 */
class SummariesTab(private val host: MainActivity) {
    private enum class Filter(val label: String) { ALL("All"), SUMMARIES("Summaries"), COMMANDS("Commands") }

    private val ui = host.ui
    private val fmt = host.fmt
    private var filter = Filter.ALL
    private val selected = LinkedHashSet<String>()
    val selecting get() = selected.isNotEmpty()

    private val adapter = Adapter()
    private val chips = HashMap<Filter, TextView>()
    private val banner = ui.text(13f, ui.fg).apply {
        background = ui.ripple(ui.rounded(ui.redSoft, 12))
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        visibility = View.GONE
        setOnClickListener { Library.refresh(host) }
    }
    private val list = ListView(host).apply {
        adapter = this@SummariesTab.adapter
        divider = null
        clipToPadding = false
        setPadding(0, 0, 0, ui.dp(8))
        setOnItemClickListener { _, _, pos, _ ->
            val e = (this@SummariesTab.adapter.getItem(pos) as? Row.Item)?.e ?: return@setOnItemClickListener
            if (selecting) toggle(e.id) else EntryActivity.open(host, e.id)
        }
        setOnItemLongClickListener { _, _, pos, _ ->
            val e = (this@SummariesTab.adapter.getItem(pos) as? Row.Item)?.e ?: return@setOnItemLongClickListener false
            toggle(e.id)
            true
        }
    }
    private val progress = ProgressBar(host).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(ui.accent)
        visibility = View.GONE
    }
    private val emptyTitle = ui.text(19f, bold = true).apply { gravity = Gravity.CENTER }
    private val emptyBody = ui.text(15f, ui.fgMuted).apply { gravity = Gravity.CENTER; setLineSpacing(ui.dpf(2f), 1f) }
    private val emptyAction: Button = ui.primaryButton("Open Settings") { host.openSettings() }
    private val empty = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(32), 0, ui.dp(32), 0)
        addView(emptyTitle)
        addView(emptyBody, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        addView(emptyAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(20) })
    }

    val view: View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
            Filter.entries.forEach { f ->
                val c = ui.chip(f.label, f == filter) { setFilter(f) }
                chips[f] = c
                addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
            }
        }
        addView(HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(4))
        })
        addView(FrameLayout(host).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    fun render() {
        // Deleted elsewhere (or by the receiver): no longer selectable.
        if (selected.retainAll(Library.index.entries.map { it.id }.toSet())) host.selectionChanged(selected.size)
        adapter.update()
        val src = Library.source
        val p = Library.problem
        val showList = adapter.count > 0
        list.visibility = if (showList) View.VISIBLE else View.GONE
        banner.visibility = if (showList && p != null) View.VISIBLE else View.GONE
        if (showList && p != null) banner.text = "${p.title}. Showing the last list it sent."
        progress.visibility = if (!showList && Library.loading && p == null) View.VISIBLE else View.GONE
        empty.visibility = if (!showList && progress.visibility != View.VISIBLE && src != null) View.VISIBLE else View.GONE
        if (p != null && Library.index.entries.isEmpty()) {
            emptyTitle.text = p.title
            emptyBody.text = p.body
            emptyAction.visibility = View.VISIBLE
            emptyAction.text = if (p.action == Receiver.Problem.Action.SETTINGS) "Open Settings" else "Try again"
            emptyAction.setOnClickListener {
                if (p.action == Receiver.Problem.Action.SETTINGS) host.openSettings() else Library.refresh(host)
            }
        } else {
            emptyTitle.text = when (filter) {
                Filter.COMMANDS -> "No commands yet"
                else -> "No recordings yet"
            }
            emptyBody.text = when {
                filter == Filter.COMMANDS -> "Double press your RTB, or hold its button, and say what to do."
                AppStatus.snapshot.serviceRunning -> "Unmute your RTB to record."
                else -> "Unmute your RTB to record. The connection is off: start it in Settings."
            }
            emptyAction.visibility = if (AppStatus.snapshot.serviceRunning || filter == Filter.COMMANDS) View.GONE else View.VISIBLE
            emptyAction.text = "Open Settings"
            emptyAction.setOnClickListener { host.openSettings() }
        }
    }

    private fun setFilter(f: Filter) {
        filter = f
        chips.forEach { (k, c) -> ui.setChip(c, k == f) }
        render()
        list.setSelection(0)
    }

    // ------------------------------------------------------------------ selection

    private fun toggle(id: String) {
        if (!selected.remove(id)) selected += id
        adapter.notifyDataSetChanged()
        host.selectionChanged(selected.size)
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        adapter.notifyDataSetChanged()
        host.selectionChanged(0)
    }

    fun confirmDelete() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        val chosen = ids.mapNotNull { Library.index[it] }
        val n = ids.size
        val live = AppStatus.snapshot.live.any { it in ids }
        val madeThings = chosen.any { it.command?.action in listOf("CREATE_NOTE", "ADD_TO_NOTE", "CREATE_REMINDER") }
        val message = buildString {
            append(if (n == 1) "Its" else "Their")
            append(" audio, transcript and summary are deleted from your receiver and this phone. This can't be undone.")
            if (madeThings) append("\n\nNotes and reminders made from ${if (n == 1) "it" else "them"} go too.")
            if (live) append("\n\nOne is recording now: it stops, and isn't transcribed.")
        }
        AlertDialog.Builder(host)
            .setTitle(if (n == 1) "Delete this recording?" else "Delete $n recordings?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                clearSelection()
                Library.delete(host, ids) { failed ->
                    if (failed > 0 && !host.isDestroyed) {
                        Toast.makeText(
                            host, "Couldn't reach your receiver: $failed not deleted. They're back in the list.", Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ list

    private sealed interface Row {
        class Day(val label: String) : Row
        class Item(val e: Entry) : Row
    }

    private inner class Adapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()

        /** Rebuild from [Library], with a header for each day. */
        fun update() {
            val out = ArrayList<Row>()
            var day: java.time.LocalDate? = null
            val now = System.currentTimeMillis()
            for (e in Library.index.entries) {
                if (filter == Filter.SUMMARIES && e.kind != Entry.Kind.SUMMARY) continue
                if (filter == Filter.COMMANDS && e.kind != Entry.Kind.COMMAND) continue
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
                    val holder = (convertView?.tag as? EntryRow) ?: EntryRow(host, ui, fmt)
                    holder.bind(r.e, live = r.e.id in AppStatus.snapshot.live, selecting = selecting, selected = r.e.id in selected)
                    holder.view
                }
            }
    }
}
