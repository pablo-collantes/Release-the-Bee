package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Reminder
import io.github.pablocollantes.releasethebee.service.ReminderAlarms
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reminders on screen, the same on Home and in To-do: a checklist row, and what a tap
 * does. A reminder with a time opens a check of that time (the model can mishear "3" as
 * "13"): keep it, change it, or open the recording. One without a time is just a to-do: a
 * tap checks it off.
 */
class ReminderViews(private val activity: Activity, private val ui: Ui, private val fmt: Format) {

    inner class Row {
        val box: CheckBox = ui.checkbox()
        val text: TextView = ui.text(16f).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        val due: TextView = ui.text(13f, ui.fgMuted)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(20), ui.dp(8))
            background = ui.ripple()
            addView(box)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
                addView(due)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4) })
            tag = this@Row
        }

        fun bind(r: Reminder, now: Long = System.currentTimeMillis()) {
            box.isChecked = r.done
            text.text = r.text
            text.setTextColor(if (r.done) ui.fgMuted else ui.fg)
            text.paintFlags = if (r.done) text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            val at = r.at
            val (label, color) = when {
                at == null -> "Added ${fmt.dayTime(r.createdAt, now)}" to ui.fgMuted
                r.done -> fmt.dayTime(at, now) to ui.fgMuted
                at < now -> "Overdue · ${fmt.dayTime(at, now)}" to ui.red
                ReminderAlarms.confirmed(activity, r) -> fmt.dayTime(at, now) to ui.accentText
                else -> "${fmt.dayTime(at, now)} · tap to check the time" to ui.accentText
            }
            due.text = label
            due.setTextColor(color)
            box.contentDescription = if (r.done) "Done. Tap to mark not done" else "Not done. Tap to check off"
        }
    }

    /** A row's view with its taps wired: the box checks off, the rest opens [open]. */
    fun row(r: Reminder, existing: View? = null): View {
        val h = (existing?.tag as? Row) ?: Row()
        h.bind(r)
        h.view.setOnClickListener { open(r) }
        h.box.setOnClickListener { Library.setDone(activity, r.id, !r.done) }
        return h.view
    }

    fun open(r: Reminder) {
        val at = r.at ?: return Library.setDone(activity, r.id, !r.done)
        val d = AlertDialog.Builder(activity)
            .setTitle(r.text)
            .setMessage(
                "${fmt.dayTime(at)}\n\nSet by voice: ${fmt.dayTime(r.createdAt)}. " +
                    if (r.done) "It's checked off." else "Is that the right time?",
            )
            .setNegativeButton("Change time") { _, _ -> pickTime(r, at) }
            .setNeutralButton("Recording") { _, _ -> EntryActivity.open(activity, r.id) }
        if (!r.done) d.setPositiveButton("Looks right") { _, _ -> ReminderAlarms.confirm(activity, r); Library.touch() }
        d.show()
    }

    private fun pickTime(r: Reminder, at: Long) {
        val zone = ZoneId.systemDefault()
        val t = Instant.ofEpochMilli(at).atZone(zone)
        DatePickerDialog(activity, { _, y, m, day ->
            TimePickerDialog(activity, { _, h, min ->
                val ms = ZonedDateTime.of(y, m + 1, day, h, min, 0, 0, zone).toInstant().toEpochMilli()
                ReminderAlarms.confirm(activity, r.copy(at = ms))
                Library.setWhen(activity, r.id, fmt.iso(ms))
            }, t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(activity)).show()
        }, t.year, t.monthValue - 1, t.dayOfMonth).show()
    }
}
