package io.github.pablocollantes.releasethebee.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.api.Entry

/**
 * One recording in a list: title and length, time, kind and status, and the start of its
 * summary. In a selection it gets a check mark and a tint. [live]: the phone is recording
 * it right now.
 */
class EntryRow(ctx: Context, private val ui: Ui, private val fmt: Format) {
    private val title = ui.text(16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val duration = ui.text(13f, ui.fgMuted)
    private val time = ui.text(13f, ui.fgMuted)
    private val kind = ui.badge("Summary", ui.surface, ui.fgMuted)
    private val statusDot = ui.dot(ui.gray, 6)
    private val status = ui.text(13f, ui.fgMuted)
    private val preview = ui.text(14f, ui.fgMuted).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(ui.dpf(1f), 1f)
    }
    private val check = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_check)
        imageTintList = ColorStateList.valueOf(ui.onAccent)
        background = ui.rounded(ui.accent, 14)
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
    }
    private val text = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.row(title.weighted(), duration.start(12)))
        addView(
            ui.row(time, kind, statusDot.apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)).apply { marginStart = ui.dp(10) }
            }, status.start(6)).apply { setPadding(0, ui.dp(6), 0, 0) },
        )
        addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(6) })
    }
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        addView(check, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(14) })
        addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        tag = this@EntryRow
    }

    fun bind(e: Entry, live: Boolean, selecting: Boolean = false, selected: Boolean = false) {
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
        status.text = if (live) "Recording now" else fmt.status(e)
        val c = if (live) ui.red else statusColor(ui, e.status)
        status.setTextColor(if (e.status == Entry.Status.DONE && !live) ui.fgMuted else c)
        ui.setDot(statusDot, c)
        preview.text = e.preview ?: e.error?.let { "Failed: $it" } ?: ""
        preview.visibility = if (preview.text.isNullOrBlank()) View.GONE else View.VISIBLE
        // Selecting: a filled circle with a check, or an empty ring.
        check.visibility = if (selecting) View.VISIBLE else View.GONE
        check.background = if (selected) ui.rounded(ui.accent, 14) else ui.rounded(0, 14, ui.fgMuted)
        check.imageAlpha = if (selected) 255 else 0
        view.setBackgroundColor(if (selected) ui.accentSoft else 0)
    }

    private fun View.weighted(): View = apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }

    private fun View.start(marginDp: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(marginDp) }
    }

    companion object {
        fun statusColor(ui: Ui, s: Entry.Status) = when (s) {
            Entry.Status.RECORDING, Entry.Status.FAILED -> ui.red
            Entry.Status.DONE -> ui.green
            else -> ui.accentText
        }
    }
}
