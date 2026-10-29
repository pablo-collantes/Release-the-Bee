package io.github.pablocollantes.releasethebee.ui

import io.github.pablocollantes.releasethebee.api.EntryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class FormatTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val fmt = Format(zone, Locale.UK, use24h = true)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `untitled entries fall back to kind and time`() {
        val started = at(2026, 10, 28, 14, 38)
        val e = EntryJson.entry("""{"id":"a","started_at":$started,"status":"transcribing","title":null}""")
        assertEquals("Recording · 14:38", fmt.title(e))
        assertEquals("Transcribing…", fmt.status(e))
        val c = EntryJson.entry("""{"id":"b","kind":"command","started_at":$started,"status":"summarizing","title":" "}""")
        assertEquals("Command · 14:38", fmt.title(c))
        assertEquals("Command", fmt.kind(c))
        val t = EntryJson.entry("""{"id":"c","started_at":$started,"status":"done","title":"Standup"}""")
        assertEquals("Standup", fmt.title(t))
        assertEquals("2:38 PM", Format(zone, Locale.US, use24h = false).clock(started))
    }

    @Test fun `days read naturally`() {
        val now = at(2026, 10, 28, 9, 0)
        assertEquals("Today", fmt.day(at(2026, 10, 28, 0, 5), now))
        assertEquals("Yesterday", fmt.day(at(2026, 10, 27, 23, 55), now))
        assertEquals("Saturday", fmt.day(at(2026, 10, 24, 12, 0), now))
        assertEquals("Mon 12 Oct", fmt.day(at(2026, 10, 12, 12, 0), now))
        assertEquals("Fri 12 Dec 2025", fmt.day(at(2025, 12, 12, 12, 0), now))
    }

    @Test fun `due times read naturally`() {
        val now = at(2026, 10, 28, 20, 0)
        assertEquals("Today 21:30", fmt.dayTime(at(2026, 10, 28, 21, 30), now))
        assertEquals("Tomorrow 09:00", fmt.dayTime(at(2026, 10, 29, 9, 0), now))
        assertEquals("Yesterday 18:07", fmt.dayTime(at(2026, 10, 27, 18, 7), now))
        assertEquals("Saturday 15:00", fmt.dayTime(at(2026, 10, 31, 15, 0), now))
        assertEquals("Wed 4 Nov, 15:00", fmt.dayTime(at(2026, 11, 4, 15, 0), now))
    }

    @Test fun `times for the receiver are ISO 8601 with the offset`() {
        assertEquals("2026-10-29T15:00:00+01:00", fmt.iso(at(2026, 10, 29, 15, 0)))
        assertEquals("2026-07-01T09:30:00+02:00", fmt.iso(at(2026, 7, 1, 9, 30)))
    }

    @Test fun `durations`() {
        assertEquals("0:42", Format.clockDuration(42.9))
        assertEquals("12:04", Format.clockDuration(724.0))
        assertEquals("1:10:04", Format.clockDuration(4204.2))
        assertEquals("42 s", Format.shortDuration(42.0))
        assertEquals("12 min", Format.shortDuration(724.0))
        assertEquals("1 h 10 min", Format.shortDuration(4204.2))
        assertEquals("2 h", Format.shortDuration(7200.0))
    }

    @Test fun `speakers and commands`() {
        assertEquals("Speaker 1", Format.speaker("S1"))
        assertEquals("Speaker 12", Format.speaker("S12"))
        assertEquals("Ana", Format.speaker("Ana"))
        assertNull(Format.speaker(null))
        assertEquals(0, Format.speakerIndex("S1"))
        assertEquals(-1, Format.speakerIndex(null))
        assertEquals("Create a reminder", Format.commandAction("CREATE_REMINDER"))
        assertEquals("Not understood", Format.commandAction("UNKNOWN"))
        assertEquals("Thu 29 Oct, 09:00", Format.whenLabel("2026-10-29T09:00:00+01:00", zone, Locale.UK))
        assertEquals("tomorrow", Format.whenLabel("tomorrow", zone, Locale.UK))
    }
}
