package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Note
import io.github.pablocollantes.releasethebee.data.Notes

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
