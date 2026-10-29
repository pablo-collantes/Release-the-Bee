package io.github.pablocollantes.releasethebee.api

import io.github.pablocollantes.releasethebee.sink.EntryPush

/**
 * The recordings list as the app knows it, newest first, built from one full fetch and
 * then kept current with cheap `?since=` fetches and WebSocket pushes. Not thread-safe:
 * the UI owns it on the main thread.
 */
class EntryIndex {

    var entries: List<Entry> = emptyList()
        private set

    /** The server clock of the last response: what to send as `since` next time. */
    var cursor: Long? = null
        private set

    /** A full list has been loaded, so the next fetch can be a `since` fetch. */
    val loaded: Boolean get() = cursor != null

    operator fun get(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** A full `GET /api/entries`. */
    fun replaceAll(page: EntryPage) {
        entries = page.entries.sortedWith(NEWEST_FIRST)
        cursor = page.now
    }

    /** What was saved on disk last run. */
    fun restore(saved: List<Entry>, savedCursor: Long?) {
        entries = saved.sortedWith(NEWEST_FIRST)
        cursor = savedCursor
    }

    /** A `?since=` response: only what changed, and what was deleted. Entries it doesn't mention stay. */
    fun merge(page: EntryPage) {
        if (page.entries.isNotEmpty() || page.deleted.isNotEmpty()) {
            val byId = LinkedHashMap<String, Entry>()
            entries.forEach { byId[it.id] = it }
            page.entries.forEach { byId[it.id] = it }
            page.deleted.forEach { byId.remove(it) }
            entries = byId.values.sortedWith(NEWEST_FIRST)
        }
        if (page.now != null) cursor = page.now
    }

    /** Drop entries (deleted here, or the receiver said so). True if any were known. */
    fun remove(ids: Collection<String>): Boolean {
        val before = entries.size
        entries = entries.filter { it.id !in ids }
        return entries.size != before
    }

    /**
     * One entry, e.g. from `GET /api/entries/<id>`. Kept light: the list never holds a
     * transcript or a summary, only what a list response carries.
     */
    fun upsert(e: Entry) = merge(EntryPage(listOf(e.copy(transcript = null, summary = null)), null))

    /**
     * Apply a push in place. False if the entry is new to us, so the caller should fetch:
     * a push carries only id, kind, status and title.
     */
    fun applyPush(p: EntryPush): Boolean {
        val old = get(p.id) ?: return false
        val updated = old.copy(
            status = p.status?.let { Entry.Status.of(it) } ?: old.status,
            title = p.title ?: old.title,
            kind = p.kind?.let { Entry.Kind.of(it) } ?: old.kind,
        )
        entries = entries.map { if (it.id == p.id) updated else it }
        return true
    }

    private companion object {
        val NEWEST_FIRST = compareByDescending<Entry> { it.startedAt }.thenBy { it.id }
    }
}
