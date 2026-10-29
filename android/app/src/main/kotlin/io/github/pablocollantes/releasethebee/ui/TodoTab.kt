package io.github.pablocollantes.releasethebee.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Reminder
import io.github.pablocollantes.releasethebee.data.Reminders

/**
 * Reminders as a checklist: what's still to do (due ones first, soonest at the top), then
 * what's done. Those with a time also ring on the phone.
 */
class TodoTab(private val host: MainActivity) {
    private val ui = host.ui
    private val adapter = Adapter()

    private val list = ListView(host).apply {
        adapter = this@TodoTab.adapter
        divider = null
        clipToPadding = false
        setPadding(0, 0, 0, ui.dp(8))
    }
    private val empty = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(32), 0, ui.dp(32), 0)
        addView(ui.text(19f, bold = true, value = "No reminders yet").apply { gravity = Gravity.CENTER })
        addView(ui.text(15f, ui.fgMuted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(ui.dpf(2f), 1f)
            text = "Double press your RTB (or hold its button) and say “Remind me tomorrow at 9 to call Ana”. " +
                "Ones with a time ring on this phone."
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(8) })
    }

    val view: View = FrameLayout(host).apply {
        addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    fun render() {
        adapter.update()
        list.visibility = if (adapter.count > 0) View.VISIBLE else View.GONE
        empty.visibility = if (adapter.count > 0) View.GONE else View.VISIBLE
    }

    private sealed interface Row {
        class Header(val label: String) : Row
        class Item(val r: Reminder) : Row
    }

    private inner class Adapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()

        fun update() {
            val all = Reminders.of(Library.index.entries)
            val active = Reminders.active(all)
            val done = Reminders.completed(all)
            rows = buildList {
                if (active.isNotEmpty()) add(Row.Header("To do · ${active.size}"))
                active.forEach { add(Row.Item(it)) }
                if (done.isNotEmpty()) add(Row.Header("Done · ${done.size}"))
                done.forEach { add(Row.Item(it)) }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false        // rows handle their own taps

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val r = rows[position]) {
                is Row.Header -> ((convertView as? TextView) ?: ui.text(12f, ui.fgMuted, bold = true).apply {
                    letterSpacing = 0.08f
                    setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(4))
                }).apply { text = r.label.uppercase() }
                is Row.Item -> host.reminders.row(r.r, convertView).apply {
                    setPadding(ui.dp(8), ui.dp(6), ui.dp(20), ui.dp(6))
                }
            }
    }
}
