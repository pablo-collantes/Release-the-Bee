package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry

/**
 * Notes, read out of voice commands: "New note: gift ideas for Sam…" starts one, "Add milk
 * to my shopping list" adds to one. A note is every CREATE_NOTE and ADD_TO_NOTE with the
 * same title, give or take case, punctuation and a trailing "list" or "note". The receiver
 * shows the model the titles that exist, so it reuses them.
 */
class Note(val key: String, val title: String, val items: List<Item>) {
    /** One thing said into the note. [entryId] is the command's recording. */
    class Item(val entryId: String, val text: String, val at: Long)

    val updatedAt: Long get() = items.maxOf { it.at }
}

object Notes {

    /** Newest activity first; items oldest first, like lines written into a note. */
    fun of(entries: List<Entry>): List<Note> {
        val groups = LinkedHashMap<String, MutableList<Pair<Entry, Entry.Command>>>()
        for (e in entries) {
            val c = e.command ?: continue
            if (e.kind != Entry.Kind.COMMAND || e.status != Entry.Status.DONE) continue
            if (c.action != "CREATE_NOTE" && c.action != "ADD_TO_NOTE") continue
            groups.getOrPut(key(c.note)) { mutableListOf() } += e to c
        }
        return groups.map { (key, list) ->
            val oldestFirst = list.sortedBy { it.first.startedAt }
            // The title it was created with; else the one used last.
            val title = oldestFirst.firstOrNull { it.second.action == "CREATE_NOTE" }?.second?.note
                ?: oldestFirst.last().second.note
            Note(
                key,
                title?.takeIf { it.isNotBlank() } ?: UNTITLED,
                oldestFirst.map { (e, c) -> Note.Item(e.id, c.text?.takeIf { it.isNotBlank() } ?: c.note ?: "", e.startedAt) },
            )
        }.sortedByDescending { it.updatedAt }
    }

    /** "My Shopping List!" and "shopping" are the same note. */
    fun key(title: String?): String {
        var k = title.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotEmpty() }
        if (k.firstOrNull() in listOf("my", "the")) k = k.drop(1)
        if (k.size > 1 && k.last() in listOf("list", "note", "notes")) k = k.dropLast(1)
        return k.joinToString(" ")
    }

    /** The note as plain text, for sharing. */
    fun text(n: Note): String = buildString {
        append(n.title)
        n.items.forEach { append("\n- ").append(it.text) }
    }

    const val UNTITLED = "Untitled note"
}
