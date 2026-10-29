package io.github.pablocollantes.releasethebee.data

import io.github.pablocollantes.releasethebee.api.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PhoneCopiesTest {

    private val zone = ZoneId.of("America/Chicago")
    private val day = 24 * 3600 * 1000L
    private val start = ZonedDateTime.of(2026, 10, 20, 18, 3, 59, 0, zone).toInstant().toEpochMilli()
    private val now = start + 8 * day

    private val done = summary("72152462-b689-4c7f-aed6-195e938335d2", start)
    private val queued = summary("bf5f666f-fcff-4dcb-9ea6-369f691b87a3", start + 60_000, Entry.Status.QUEUED)

    @Test fun `a copy is matched by the session id in its name`() {
        val c = PhoneCopies.Copy("2026-10-20/180359_72152462.aac", start)
        assertEquals(done.id, PhoneCopies.match(c, listOf(queued, done), zone)?.id)
    }

    @Test fun `an older copy, named after the device, is matched by when it started`() {
        val c = PhoneCopies.Copy("2026-10-20/180359_b7d4ba96.aac", start)
        assertEquals(done.id, PhoneCopies.match(c, listOf(queued, done), zone)?.id)
        assertNull(PhoneCopies.match(PhoneCopies.Copy("2026-10-20/180400_b7d4ba96.aac", start), listOf(done), zone))
        assertNull(PhoneCopies.match(PhoneCopies.Copy("notes.txt", start), listOf(done), zone))
    }

    @Test fun `copies go a week after recording, once transcribed`() {
        val copies = listOf(
            PhoneCopies.Copy("2026-10-20/180359_72152462.aac", start + 60_000),          // done, 8 days old
            PhoneCopies.Copy("2026-10-20/180459_bf5f666f.aac", start + 120_000),         // still queued
            PhoneCopies.Copy("2026-10-27/120000_aaaaaaaa.aac", now - 2 * day),            // too new (and unknown)
            PhoneCopies.Copy("2026-10-19/120000_cccccccc.aac", start - day),              // the receiver never got it
        )
        assertEquals(listOf(copies[0]), PhoneCopies.expired(copies, listOf(done, queued), now, zone))
    }
}
