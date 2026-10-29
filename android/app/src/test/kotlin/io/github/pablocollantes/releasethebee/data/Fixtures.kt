package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry

/** A processed voice command, as the receiver lists it. */
fun command(
    id: String,
    at: Long,
    action: String,
    note: String? = null,
    text: String? = null,
    whenIso: String? = null,
    done: Boolean = false,
    status: Entry.Status = Entry.Status.DONE,
) = Entry(
    id = id, device = "dev", kind = Entry.Kind.COMMAND, parent = null, trigger = "double_press",
    startedAt = at, endedAt = at + 5_000, durationS = 5.0, status = status, error = null,
    title = null, preview = null, speakers = null,
    command = Entry.Command(action, note, text, whenIso, "gemma", done),
)

fun summary(id: String, at: Long, status: Entry.Status = Entry.Status.DONE) = Entry(
    id = id, device = "dev", kind = Entry.Kind.SUMMARY, parent = null, trigger = "button",
    startedAt = at, endedAt = at + 60_000, durationS = 60.0, status = status, error = null,
    title = "A talk", preview = null, speakers = 2,
)
