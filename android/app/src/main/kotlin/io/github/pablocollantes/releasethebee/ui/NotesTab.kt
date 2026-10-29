package io.github.pablocollantes.releasethebee.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Note
import io.github.pablocollantes.releasethebee.data.Notes

/** Your notes, the most recently added to first. Tap one to read it. */
class NotesTab(private val host: MainActivity) {
    private val ui = host.ui
    private val fmt = host.fmt
    private val adapter = Adapter()

    private val list = ListView(host).apply {
        adapter = this@NotesTab.adapter
        divider = null
        clipToPadding = false
        setPadding(0, 0, 0, ui.dp(8))
        setOnItemClickListener { _, _, pos, _ -> NoteActivity.open(host, this@NotesTab.adapter.items[pos].key) }
    }
    private val empty = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(32), 0, ui.dp(32), 0)
        addView(ui.text(19f, bold = true, value = "No notes yet").apply { gravity = Gravity.CENTER })
        addView(ui.text(15f, ui.fgMuted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(ui.dpf(2f), 1f)
            text = "Double press your RTB (or hold its button) and say “New note: gift ideas for Sam”, " +
                "or “Add milk to my shopping list”."
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(8) })
    }

    val view: View = FrameLayout(host).apply {
        addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    fun render() {
        adapter.set(Notes.of(Library.index.entries))
        list.visibility = if (adapter.count > 0) View.VISIBLE else View.GONE
        empty.visibility = if (adapter.count > 0) View.GONE else View.VISIBLE
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<Note> = emptyList()
            private set

        fun set(notes: List<Note>) {
            items = notes
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? NoteRow) ?: NoteRow()
            h.bind(items[position])
            return h.view
        }
    }

    private inner class NoteRow {
        val title = ui.text(16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text(13f, ui.fgMuted)
        val last = ui.text(14f, ui.fgMuted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val view = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(title)
            addView(meta, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(2) })
            addView(last, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            tag = this@NoteRow
        }

        fun bind(n: Note) {
            title.text = n.title
            val count = n.items.size
            meta.text = "$count ${if (count == 1) "item" else "items"} · ${fmt.dayTime(n.updatedAt)}"
            last.text = n.items.last().text
        }
    }
}
