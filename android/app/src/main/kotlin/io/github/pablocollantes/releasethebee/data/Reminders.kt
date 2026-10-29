package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry
import java.time.OffsetDateTime

/**
 * A reminder from a voice command ("Remind me tomorrow at 3 to call Ana"). [at] is when
 * it's due, null if no time was said: then it's just a to-do. [id] is the command's
 * recording, which is also where [done] is kept (the receiver stores it).
 */
data class Reminder(val id: String, val text: String, val at: Long?, val done: Boolean, val createdAt: Long)

object Reminders {

    fun of(entries: List<Entry>): List<Reminder> = entries.mapNotNull { e ->
        val c = e.command ?: return@mapNotNull null
        if (e.kind != Entry.Kind.COMMAND || e.status != Entry.Status.DONE || c.action != "CREATE_REMINDER") return@mapNotNull null
        Reminder(e.id, c.text?.takeIf { it.isNotBlank() } ?: "Reminder", c.whenIso?.let { parse(it) }, c.done, e.startedAt)
    }

    /** Due first (soonest, so overdue ones lead), then the ones without a time, newest first. */
    fun active(all: List<Reminder>): List<Reminder> = all.filter { !it.done }.sortedWith(
        compareBy<Reminder> { it.at == null }.thenBy { it.at ?: 0L }.thenByDescending { it.createdAt },
    )

    /** Most recent first. */
    fun completed(all: List<Reminder>): List<Reminder> = all.filter { it.done }.sortedByDescending { it.at ?: it.createdAt }

    fun parse(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
