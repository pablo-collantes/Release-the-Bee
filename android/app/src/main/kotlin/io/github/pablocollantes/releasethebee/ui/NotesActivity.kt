package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Note
import io.github.pablocollantes.releasethebee.data.Notes

/** Your notes, the most recently added to first. Tap one to read it. */
class NotesActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var fmt: Format
    private val adapter = Adapter()
    private lateinit var list: ListView
    private lateinit var empty: View

    private val onLibrary: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(this))
        Library.load(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        Library.addListener(onLibrary)
        render()
        Library.refresh(this)
    }

    override fun onStop() {
        Library.removeListener(onLibrary)
        super.onStop()
    }

    private fun render() {
        adapter.set(Notes.of(Library.index.entries))
        list.visibility = if (adapter.count > 0) View.VISIBLE else View.GONE
        empty.visibility = if (adapter.count > 0) View.GONE else View.VISIBLE
    }

    private fun buildUi(): View {
        val (bar, _) = ui.topBar("Notes", { finish() })
        list = ListView(this).apply {
            adapter = this@NotesActivity.adapter
            divider = null
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> NoteActivity.open(this@NotesActivity, this@NotesActivity.adapter.items[pos].key) }
        }
        empty = LinearLayout(this).apply {
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
        val body = FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val capped = CappedWidth(this, ui.dp(760)).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        Ui.edgeToEdge(this, capped) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            list.setPadding(0, 0, 0, bottom + ui.dp(8))
        }
        return capped
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
        val view = LinearLayout(this@NotesActivity).apply {
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

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, NotesActivity::class.java))
    }
}

/** One note: everything said into it, oldest first. Tap a line for its recording; share it as text. */
class NoteActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var fmt: Format
    private lateinit var key: String
    private lateinit var titleView: TextView
    private lateinit var lines: LinearLayout
    private var note: Note? = null

    private val onLibrary: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        ui = Ui(this)
        fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(this))
        Library.load(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        Library.addListener(onLibrary)
        render()
    }

    override fun onStop() {
        Library.removeListener(onLibrary)
        super.onStop()
    }

    private fun render() {
        val n = Notes.of(Library.index.entries).firstOrNull { it.key == key }
        note = n
        if (n == null) return finish()          // its last recording was deleted
        titleView.text = n.title
        lines.removeAllViews()
        n.items.forEach { item ->
            lines.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.ripple()
                setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10))
                setOnClickListener { EntryActivity.open(this@NoteActivity, item.entryId) }
                addView(ui.text(16f, value = item.text).apply { setLineSpacing(ui.dpf(3f), 1f) })
                addView(ui.text(12f, ui.fgMuted, value = fmt.dayTime(item.at)), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = ui.dp(4)
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun share() {
        val n = note ?: return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, Notes.text(n)),
            "Share note",
        ))
    }

    private fun buildUi(): View {
        val (bar, _) = ui.topBar("", { finish() }, ui.icon(R.drawable.ic_share, "Share") { share() })
        titleView = ui.text(24f, bold = true).apply {
            letterSpacing = -0.01f
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8))
        }
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            clipToPadding = false
            addView(LinearLayout(this@NoteActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(lines)
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        val capped = CappedWidth(this, ui.dp(760)).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        Ui.edgeToEdge(this, capped) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            scroll.setPadding(0, 0, 0, bottom + ui.dp(16))
        }
        return capped
    }

    companion object {
        private const val EXTRA_KEY = "note_key"

        fun open(ctx: Context, key: String) =
            ctx.startActivity(Intent(ctx, NoteActivity::class.java).putExtra(EXTRA_KEY, key))
    }
}
