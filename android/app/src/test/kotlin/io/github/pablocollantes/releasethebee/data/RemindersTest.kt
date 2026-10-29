package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemindersTest {

    private val entries = listOf(
        command("later", 1_000, "CREATE_REMINDER", text = "Move the car", whenIso = "2026-10-29T15:00:00-05:00"),
        command("soon", 2_000, "CREATE_REMINDER", text = "Call Ana", whenIso = "2026-10-28T18:07:00-05:00"),
        command("todo-old", 3_000, "CREATE_REMINDER", text = "Buy stamps"),
        command("todo-new", 4_000, "CREATE_REMINDER", text = "Water plants"),
        command("did", 5_000, "CREATE_REMINDER", text = "Homework", whenIso = "2026-10-27T19:05:00-05:00", done = true),
        command("note", 6_000, "CREATE_NOTE", note = "Shopping", text = "Milk"),
        command("busy", 7_000, "CREATE_REMINDER", text = "x", status = Entry.Status.SUMMARIZING),
    )

    @Test fun `reminders come from finished CREATE_REMINDER commands`() {
        val all = Reminders.of(entries)
        assertEquals(setOf("later", "soon", "todo-old", "todo-new", "did"), all.map { it.id }.toSet())
        val soon = all.first { it.id == "soon" }
        assertEquals(1_793_228_820_000L, soon.at)          // 2026-10-28 18:07 at -05:00
        assertNull(all.first { it.id == "todo-old" }.at)
    }

    @Test fun `to do lists the soonest due first, then the ones without a time, newest first`() {
        assertEquals(listOf("soon", "later", "todo-new", "todo-old"), Reminders.active(Reminders.of(entries)).map { it.id })
    }

    @Test fun `done ones are listed apart`() {
        assertEquals(listOf("did"), Reminders.completed(Reminders.of(entries)).map { it.id })
    }

    @Test fun `a time that doesn't parse means no time`() {
        assertNull(Reminders.of(listOf(command("x", 1, "CREATE_REMINDER", text = "t", whenIso = "Friday"))).single().at)
    }
}
