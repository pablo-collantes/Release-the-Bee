package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesTest {

    @Test fun `adding to a note joins the one it was created as, give or take wording`() {
        val notes = Notes.of(listOf(
            command("c3", 3_000, "ADD_TO_NOTE", note = "my shopping list!", text = "Eggs"),
            command("c1", 1_000, "CREATE_NOTE", note = "Shopping", text = "Milk"),
            command("c2", 2_000, "CREATE_NOTE", note = "Gift ideas for Sam", text = "A chalk bag"),
        ))
        assertEquals(listOf("Shopping", "Gift ideas for Sam"), notes.map { it.title })
        assertEquals(listOf("Milk", "Eggs"), notes[0].items.map { it.text })
        assertEquals(listOf("c1", "c3"), notes[0].items.map { it.entryId })
        assertEquals(3_000L, notes[0].updatedAt)
    }

    @Test fun `only finished note commands count`() {
        val notes = Notes.of(listOf(
            command("r", 1_000, "CREATE_REMINDER", text = "Call Ana"),
            command("u", 2_000, "UNKNOWN", text = "Hello?"),
            command("q", 3_000, "CREATE_NOTE", note = "Later", text = "x", status = Entry.Status.SUMMARIZING),
            summary("s", 4_000),
        ))
        assertEquals(emptyList<Note>(), notes)
    }

    @Test fun `a note with no title or text still shows`() {
        val n = Notes.of(listOf(command("c", 1_000, "CREATE_NOTE", note = "Message Zulun", text = null))).single()
        assertEquals("Message Zulun", n.title)
        assertEquals("Message Zulun", n.items.single().text)
        assertEquals(Notes.UNTITLED, Notes.of(listOf(command("d", 1_000, "ADD_TO_NOTE", note = null, text = "x"))).single().title)
    }

    @Test fun `keys ignore case, punctuation, my and a trailing list`() {
        assertEquals("shopping", Notes.key("My Shopping List!"))
        assertEquals("shopping", Notes.key("the shopping note"))
        assertEquals("list", Notes.key("List"))
        assertEquals("", Notes.key(null))
    }

    @Test fun `shared text is the title and one line per item`() {
        val n = Notes.of(listOf(
            command("c1", 1_000, "CREATE_NOTE", note = "Shopping", text = "Milk"),
            command("c2", 2_000, "ADD_TO_NOTE", note = "Shopping", text = "Eggs"),
        )).single()
        assertEquals("Shopping\n- Milk\n- Eggs", Notes.text(n))
    }
}
