package io.github.pablocollantes.releasethebee.ui

import io.github.pablocollantes.releasethebee.api.Entry
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** Words and numbers for the screens. Plain JVM, so it can be tested without a device. */
class Format(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val use24h: Boolean = true,
) {
    private val clockFmt = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
    private val weekdayFmt = DateTimeFormatter.ofPattern("EEEE", locale)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val dateYearFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)

    /** "14:38" (or "2:38 PM"). */
    fun clock(ms: Long): String = clockFmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "Today", "Yesterday", "Monday" within the week, then "Mon 27 Oct" (with the year if not this one). */
    fun day(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val ago = ChronoUnit.DAYS.between(d, today)
        return when {
            ago == 0L -> "Today"
            ago == 1L -> "Yesterday"
            ago in 2..6 -> weekdayFmt.format(d)
            d.year == today.year -> dateFmt.format(d)
            else -> dateYearFmt.format(d)
        }
    }

    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** "Tue 28 Oct" (with the year if not this one). */
    fun date(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        val d = localDate(ms)
        return if (d.year == localDate(nowMs).year) dateFmt.format(d) else dateYearFmt.format(d)
    }

    /** The title, or "Recording · 14:38" / "Command · 14:38" until the receiver has one. */
    fun title(e: Entry): String =
        e.title?.takeIf { it.isNotBlank() } ?: "${if (e.kind == Entry.Kind.COMMAND) "Command" else "Recording"} · ${clock(e.startedAt)}"

    fun hasRealTitle(e: Entry) = !e.title.isNullOrBlank()

    /** Kind as a badge word. */
    fun kind(e: Entry) = if (e.kind == Entry.Kind.COMMAND) "Command" else "Summary"

    fun status(e: Entry): String = when (e.status) {
        Entry.Status.RECORDING -> "Recording"
        Entry.Status.QUEUED -> "Queued"
        Entry.Status.TRANSCRIBING -> "Transcribing…"
        Entry.Status.DIARIZING -> "Finding speakers…"
        Entry.Status.SUMMARIZING -> if (e.kind == Entry.Kind.COMMAND) "Reading the command…" else "Summarizing…"
        Entry.Status.DONE -> "Done"
        Entry.Status.FAILED -> "Failed"
        Entry.Status.OTHER -> "Processing…"
    }

    companion object {
        /** Player time: "0:42", "12:04", "1:10:04". */
        fun clockDuration(seconds: Double): String {
            val s = seconds.coerceAtLeast(0.0).toLong()
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        /** List time: "42 s", "12 min", "1 h 10 min". */
        fun shortDuration(seconds: Double): String {
            val s = seconds.coerceAtLeast(0.0).roundToLong()
            if (s < 60) return "$s s"
            val totalMin = (s + 30) / 60
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h == 0L -> "$m min"
                m == 0L -> "$h h"
                else -> "$h h $m min"
            }
        }

        /** "S1" → "Speaker 1"; anything else as it is. */
        fun speaker(label: String?): String? {
            if (label.isNullOrBlank()) return null
            val n = label.removePrefix("S").toIntOrNull()
            return if (label.startsWith("S") && n != null) "Speaker $n" else label
        }

        /** Speaker number for picking a color: S1 → 0, S2 → 1, unknown → -1. */
        fun speakerIndex(label: String?): Int = label?.removePrefix("S")?.toIntOrNull()?.minus(1) ?: -1

        fun commandAction(action: String): String = when (action) {
            "CREATE_NOTE" -> "Create a note"
            "ADD_TO_NOTE" -> "Add to a note"
            "CREATE_REMINDER" -> "Create a reminder"
            else -> "Not understood"
        }

        /** ISO 8601 with offset → "Tue 28 Oct, 09:00" in [zone]; the raw text if it doesn't parse. */
        fun whenLabel(iso: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
            runCatching {
                OffsetDateTime.parse(iso).atZoneSameInstant(zone)
                    .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale))
            }.getOrDefault(iso)
    }
}
