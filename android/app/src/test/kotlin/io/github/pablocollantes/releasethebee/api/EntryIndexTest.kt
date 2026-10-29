package io.github.pablocollantes.releasethebee.api

import io.github.pablocollantes.releasethebee.data.command
import io.github.pablocollantes.releasethebee.data.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryIndexTest {

    @Test fun `a since page applies changes and deletions`() {
        val i = EntryIndex()
        i.replaceAll(EntryPage(listOf(summary("a", 1), summary("b", 2), summary("c", 3)), now = 10))
        i.merge(EntryPage(listOf(summary("d", 4)), now = 20, deleted = listOf("b", "zz")))
        assertEquals(listOf("d", "c", "a"), i.entries.map { it.id })
        assertEquals(20L, i.cursor)
    }

    @Test fun `remove says whether anything went`() {
        val i = EntryIndex()
        i.replaceAll(EntryPage(listOf(summary("a", 1)), now = 1))
        assertFalse(i.remove(listOf("x")))
        assertTrue(i.remove(listOf("a")))
        assertNull(i["a"])
    }

    @Test fun `upsert keeps the list light`() {
        val i = EntryIndex()
        val full = summary("a", 1).copy(
            transcript = Entry.Transcript("en", emptyList()),
            summary = Entry.Summary("t", "s", emptyList(), null),
        )
        i.upsert(full)
        assertNull(i["a"]!!.transcript)
        assertNull(i["a"]!!.summary)
    }

    @Test fun `the saved form reads back the same`() {
        val c = command("r", 1_761_651_534_000, "CREATE_REMINDER", text = "Call Ana", whenIso = "2026-10-29T15:00:00-05:00", done = true)
        val back = EntryJson.entry(EntryJson.light(c).toString())
        assertEquals(c, back)
        val s = summary("s", 5).copy(status = Entry.Status.OTHER, durationS = null, endedAt = null, audio = Entry.Audio(10, "audio/aac"))
        val sBack = EntryJson.entry(EntryJson.light(s).toString())
        assertEquals(Entry.Status.OTHER, sBack.status)
        assertNull(sBack.endedAt)
        assertEquals(10L, sBack.audio?.bytes)
    }

    @Test fun `a list response carries deleted ids and done`() {
        val page = EntryJson.page(
            """{"entries":[{"id":"r","kind":"command","status":"done","started_at":1,
               "command":{"action":"CREATE_REMINDER","note":null,"text":"x","when":null,"model":"m","done":true}}],
               "now":5,"deleted":["gone"]}""",
        )
        assertEquals(listOf("gone"), page.deleted)
        assertTrue(page.entries.single().command!!.done)
        assertFalse(EntryJson.page("""{"entries":[{"id":"r","command":{"action":"X"}}]}""").entries.single().command!!.done)
    }
}
