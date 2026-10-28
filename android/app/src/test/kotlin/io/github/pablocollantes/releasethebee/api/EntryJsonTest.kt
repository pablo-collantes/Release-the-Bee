package io.github.pablocollantes.releasethebee.api

import io.github.pablocollantes.releasethebee.sink.EntryPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryJsonTest {

    // Shaped like docs/SERVER_API.md, with made-up content.
    private val full = """
        {
          "id": "5f0c2a91-2b1c-4c55-9d8e-0a1b2c3d4e5f",
          "device": "00112233aabbccdd",
          "kind": "summary",
          "parent": null,
          "trigger": "button",
          "started_at": 1761651534000,
          "ended_at": 1761658138000,
          "duration_s": 6604.2,
          "status": "done",
          "error": null,
          "title": "Quarterly planning sync",
          "preview": "Agreed to ship the beta on the 14th…",
          "speakers": 3,
          "transcript": {
            "language": "en",
            "segments": [
              {"start": 0.0, "end": 4.2, "speaker": "S1", "text": "Okay, let's start with the timeline."},
              {"start": 4.2, "end": 9, "speaker": null, "text": "Sure."}
            ]
          },
          "summary": {
            "title": "Quarterly planning sync",
            "summary": "Markdown. A few short paragraphs.",
            "action_items": ["Ana: release notes by Friday", "Ship the beta"],
            "model": "gemma-4-e4b"
          },
          "command": null,
          "audio": {"bytes": 6601986, "mime": "audio/mp4"},
          "some_future_field": {"ignored": true}
        }
    """.trimIndent()

    @Test fun `parses a full entry`() {
        val e = EntryJson.entry(full)
        assertEquals("5f0c2a91-2b1c-4c55-9d8e-0a1b2c3d4e5f", e.id)
        assertEquals("00112233aabbccdd", e.device)
        assertEquals(Entry.Kind.SUMMARY, e.kind)
        assertNull(e.parent)
        assertEquals("button", e.trigger)
        assertEquals(1761651534000L, e.startedAt)
        assertEquals(1761658138000L, e.endedAt)
        assertEquals(6604.2, e.durationS!!, 1e-9)
        assertEquals(Entry.Status.DONE, e.status)
        assertNull(e.error)
        assertEquals("Quarterly planning sync", e.title)
        assertEquals(3, e.speakers)
        assertEquals("en", e.transcript!!.language)
        assertEquals(2, e.transcript!!.segments.size)
        val s0 = e.transcript!!.segments[0]
        assertEquals(0.0, s0.start, 0.0)
        assertEquals(4.2, s0.end, 1e-9)
        assertEquals("S1", s0.speaker)
        assertNull("JSON null is null, not the string \"null\"", e.transcript!!.segments[1].speaker)
        assertEquals(9.0, e.transcript!!.segments[1].end, 0.0)
        assertEquals(listOf("Ana: release notes by Friday", "Ship the beta"), e.summary!!.actionItems)
        assertEquals("gemma-4-e4b", e.summary!!.model)
        assertNull(e.command)
        assertEquals(6601986L, e.audio!!.bytes)
        assertEquals("audio/mp4", e.audio!!.mime)
        assertTrue(e.hasAudio)
    }

    @Test fun `parses a command entry`() {
        val e = EntryJson.entry(
            """{"id":"c1","kind":"command","parent":"p1","trigger":"double_press","started_at":5,"ended_at":9,
               "duration_s":4,"status":"done","title":null,"preview":null,"speakers":null,
               "command":{"action":"CREATE_REMINDER","note":null,"text":"call the dentist","when":"2026-10-29T09:00:00+01:00","model":"m"}}""",
        )
        assertEquals(Entry.Kind.COMMAND, e.kind)
        assertEquals("p1", e.parent)
        assertEquals("double_press", e.trigger)
        assertNull(e.title)
        assertNull(e.speakers)
        assertEquals("CREATE_REMINDER", e.command!!.action)
        assertNull(e.command!!.note)
        assertEquals("call the dentist", e.command!!.text)
        assertEquals("2026-10-29T09:00:00+01:00", e.command!!.whenIso)
        assertNull(e.summary)
        assertNull(e.transcript)
    }

    @Test fun `a list response is list-shaped and carries the server clock`() {
        val page = EntryJson.page(
            """{"now": 1761660000000, "entries": [
                 {"id":"b","kind":"summary","started_at":200,"ended_at":null,"duration_s":null,"status":"recording","title":null,"preview":null},
                 {"id":"a","kind":"summary","started_at":100,"ended_at":150,"duration_s":50,"status":"transcribing"},
                 {"no_id": true},
                 {"id":"z","status":"some_new_step","started_at":50}
               ]}""",
        )
        assertEquals(1761660000000L, page.now)
        assertEquals(listOf("b", "a", "z"), page.entries.map { it.id })   // the one without an id is skipped
        val rec = page.entries[0]
        assertEquals(Entry.Status.RECORDING, rec.status)
        assertNull(rec.endedAt)
        assertNull(rec.durationS)
        assertFalse(rec.hasAudio)
        assertEquals(Entry.Status.TRANSCRIBING, page.entries[1].status)
        assertEquals(Entry.Status.OTHER, page.entries[2].status)
        assertEquals("missing kind means summary", Entry.Kind.SUMMARY, page.entries[2].kind)
        assertNull(EntryJson.page("""{"entries":[]}""").now)
    }

    @Test fun `statuses map from the wire`() {
        val wire = listOf("recording", "queued", "transcribing", "diarizing", "summarizing", "done", "failed")
        assertEquals(wire, wire.map { Entry.Status.of(it).wire })
        assertEquals(Entry.Status.OTHER, Entry.Status.of(null))
        assertEquals(Entry.Status.OTHER, Entry.Status.of(""))
        assertTrue(Entry.Status.DONE.finished && Entry.Status.FAILED.finished && !Entry.Status.QUEUED.finished)
    }

    // ------------------------------------------------------------------ EntryIndex

    private fun e(id: String, at: Long, status: String = "done", title: String? = null) = EntryJson.entry(
        """{"id":"$id","started_at":$at,"status":"$status","title":${title?.let { "\"$it\"" } ?: "null"}}""",
    )

    @Test fun `the index keeps newest first and merges since-fetches`() {
        val idx = EntryIndex()
        assertFalse(idx.loaded)
        idx.replaceAll(EntryPage(listOf(e("old", 1), e("new", 3), e("mid", 2, "queued")), now = 10))
        assertTrue(idx.loaded)
        assertEquals(10L, idx.cursor)
        assertEquals(listOf("new", "mid", "old"), idx.entries.map { it.id })

        // ?since= returns only what changed: one update, one new entry.
        idx.merge(EntryPage(listOf(e("mid", 2, "done", "Standup"), e("newest", 4, "recording")), now = 20))
        assertEquals(20L, idx.cursor)
        assertEquals(listOf("newest", "new", "mid", "old"), idx.entries.map { it.id })
        assertEquals("Standup", idx["mid"]!!.title)
        assertEquals(Entry.Status.DONE, idx["mid"]!!.status)

        // An empty since-fetch still moves the cursor.
        idx.merge(EntryPage(emptyList(), now = 30))
        assertEquals(30L, idx.cursor)
        assertEquals(4, idx.entries.size)
    }

    @Test fun `pushes update a known entry in place, and say when one is new`() {
        val idx = EntryIndex()
        idx.replaceAll(EntryPage(listOf(e("a", 1, "summarizing")), now = 1))
        assertTrue(idx.applyPush(EntryPush("a", "summary", "done", "Retro")))
        assertEquals(Entry.Status.DONE, idx["a"]!!.status)
        assertEquals("Retro", idx["a"]!!.title)
        // A push without a title doesn't erase the one we have.
        assertTrue(idx.applyPush(EntryPush("a", null, "queued", null)))
        assertEquals("Retro", idx["a"]!!.title)
        assertEquals(Entry.Status.QUEUED, idx["a"]!!.status)
        assertFalse(idx.applyPush(EntryPush("b", "command", "recording", null)))
        assertNull(idx["b"])
    }
}
