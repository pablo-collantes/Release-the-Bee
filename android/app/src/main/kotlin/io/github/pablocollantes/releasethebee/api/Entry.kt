package io.github.pablocollantes.releasethebee.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recording as the receiver serves it (docs/SERVER_API.md). List responses leave out
 * [transcript], [summary] and [command]; `GET /api/entries/<id>` fills them in.
 */
data class Entry(
    val id: String,
    val device: String?,
    val kind: Kind,
    val parent: String?,
    val trigger: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationS: Double?,
    val status: Status,
    val error: String?,
    val title: String?,
    val preview: String?,
    val speakers: Int?,
    val transcript: Transcript? = null,
    val summary: Summary? = null,
    val command: Command? = null,
    val audio: Audio? = null,
) {
    enum class Kind(val wire: String) {
        SUMMARY("summary"), COMMAND("command");

        companion object {
            /** `summary` is the default when the field is missing or unknown. */
            fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: SUMMARY
        }
    }

    enum class Status(val wire: String) {
        RECORDING("recording"), QUEUED("queued"), TRANSCRIBING("transcribing"), DIARIZING("diarizing"),
        SUMMARIZING("summarizing"), DONE("done"), FAILED("failed"),
        /** A status this version doesn't know: some later processing step. */
        OTHER("");

        val finished get() = this == DONE || this == FAILED

        companion object {
            fun of(wire: String?) = entries.firstOrNull { it.wire == wire && it != OTHER } ?: OTHER
        }
    }

    class Transcript(val language: String?, val segments: List<Segment>)

    /** [speaker] is `S1`, `S2`, … in order of first appearance, or null without diarization. */
    class Segment(val start: Double, val end: Double, val speaker: String?, val text: String)

    /** [summary] is Markdown. */
    class Summary(val title: String?, val summary: String?, val actionItems: List<String>, val model: String?)

    /**
     * [action] is CREATE_NOTE, ADD_TO_NOTE, CREATE_REMINDER or UNKNOWN. [note] is the target
     * note's title, [whenIso] ISO 8601 with offset (reminders), [text] the body. [done] is the
     * user's: a reminder checked off.
     */
    data class Command(
        val action: String,
        val note: String?,
        val text: String?,
        val whenIso: String?,
        val model: String?,
        val done: Boolean = false,
    )

    class Audio(val bytes: Long?, val mime: String?)

    /** Audio exists once the recording has ended. */
    val hasAudio get() = endedAt != null || status != Status.RECORDING
}

/**
 * A list response: the entries, the server clock to pass as `since` next time, and (for a
 * `since` request) the ids deleted since then.
 */
class EntryPage(val entries: List<Entry>, val now: Long?, val deleted: List<String> = emptyList())

/** Parsing of the receiver's JSON. Tolerant: unknown fields are ignored, missing ones are null. */
object EntryJson {

    fun page(text: String): EntryPage {
        val o = JSONObject(text)
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { entry(it) }.getOrNull() } }
        return EntryPage(list, o.long("now"), o.strings("deleted"))
    }

    fun entry(text: String): Entry = entry(JSONObject(text))

    fun entry(o: JSONObject): Entry {
        val id = o.str("id") ?: throw IllegalArgumentException("entry without an id")
        return Entry(
            id = id,
            device = o.str("device"),
            kind = Entry.Kind.of(o.str("kind")),
            parent = o.str("parent"),
            trigger = o.str("trigger"),
            startedAt = o.long("started_at") ?: 0L,
            endedAt = o.long("ended_at"),
            durationS = o.double("duration_s"),
            status = Entry.Status.of(o.str("status")),
            error = o.str("error"),
            title = o.str("title"),
            preview = o.str("preview"),
            speakers = o.long("speakers")?.toInt(),
            transcript = o.obj("transcript")?.let { t ->
                Entry.Transcript(
                    t.str("language"),
                    t.objects("segments").map { s ->
                        Entry.Segment(
                            start = s.double("start") ?: 0.0,
                            end = s.double("end") ?: 0.0,
                            speaker = s.str("speaker"),
                            text = s.str("text") ?: "",
                        )
                    },
                )
            },
            summary = o.obj("summary")?.let { s ->
                Entry.Summary(s.str("title"), s.str("summary"), s.strings("action_items"), s.str("model"))
            },
            command = o.obj("command")?.let { c ->
                Entry.Command(
                    c.str("action") ?: "UNKNOWN", c.str("note"), c.str("text"), c.str("when"), c.str("model"),
                    done = c.optBoolean("done", false),
                )
            },
            audio = o.obj("audio")?.let { a -> Entry.Audio(a.long("bytes"), a.str("mime")) },
        )
    }

    /**
     * The list form of [e] (no transcript or summary), as the receiver sends it: what the
     * app keeps on disk between runs. [entry] reads it back.
     */
    fun light(e: Entry): JSONObject = JSONObject()
        .put("id", e.id)
        .put("device", e.device.orNull())
        .put("kind", e.kind.wire)
        .put("parent", e.parent.orNull())
        .put("trigger", e.trigger.orNull())
        .put("started_at", e.startedAt)
        .put("ended_at", e.endedAt.orNull())
        .put("duration_s", e.durationS.orNull())
        .put("status", e.status.wire.ifEmpty { "processing" })
        .put("error", e.error.orNull())
        .put("title", e.title.orNull())
        .put("preview", e.preview.orNull())
        .put("speakers", e.speakers.orNull())
        .put("command", e.command?.let { command(it) }.orNull())
        .put("audio", e.audio?.let { JSONObject().put("bytes", it.bytes.orNull()).put("mime", it.mime.orNull()) }.orNull())

    fun command(c: Entry.Command): JSONObject = JSONObject()
        .put("action", c.action)
        .put("note", c.note.orNull())
        .put("text", c.text.orNull())
        .put("when", c.whenIso.orNull())
        .put("model", c.model.orNull())
        .put("done", c.done)

    /** JSON null: org.json drops a key put with Kotlin null. */
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL

    // org.json differs between Android and the reference library on JSON null
    // (optString gives "null" on Android), so every read goes through isNull first.
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONObject.long(k: String): Long? = if (isNull(k)) null else (opt(k) as? Number)?.toLong()
    private fun JSONObject.double(k: String): Double? =
        if (isNull(k)) null else (opt(k) as? Number)?.toDouble()?.takeIf { !it.isNaN() }
    private fun JSONObject.obj(k: String): JSONObject? = if (isNull(k)) null else optJSONObject(k)
    private fun JSONObject.objects(k: String): List<JSONObject> {
        val a = if (isNull(k)) null else optJSONArray(k)
        return if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun JSONObject.strings(k: String): List<String> {
        val a = if (isNull(k)) null else optJSONArray(k)
        return if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i) }
    }
}
