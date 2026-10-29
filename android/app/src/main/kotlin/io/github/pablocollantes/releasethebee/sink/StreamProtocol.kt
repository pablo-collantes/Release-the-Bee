package io.github.pablocollantes.releasethebee.sink

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * rtb-stream/1: what the app sends over a WebSocket (docs/STREAM_PROTOCOL.md).
 *
 * Text messages are JSON control events. Binary messages are audio:
 *
 *     [seq u32 BE][captured_at_ms u64 BE][ADTS AAC bytes]
 */
object StreamProtocol {
    const val NAME = "rtb-stream/1"
    const val AUDIO_HEADER_LEN = 12

    /** [deviceIdHex] is null while a ctrKey-only key hasn't reached its RTB yet. */
    fun hello(app: String, deviceIdHex: String?) = json(
        "type" to "hello",
        "protocol" to NAME,
        "app" to app,
        "device" to deviceIdHex,
        "codec" to "aac-adts",
        "sample_rate" to 16000,
        "channels" to 1,
    )

    fun sessionStart(s: SessionInfo, resumed: Boolean) = json(
        "type" to "session_start",
        "session" to s.id,
        "device" to s.deviceIdHex,
        "started_at" to s.startedAtMs,
        "resumed" to resumed,
        "kind" to s.kind.wire,
        "trigger" to s.trigger?.wire,
        "parent" to s.parentId,
    )

    /** [discard]: the user deleted it; the receiver throws it away instead of processing it. */
    fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean = false) =
        if (discard) json("type" to "session_end", "session" to s.id, "reason" to reason, "discard" to true)
        else json("type" to "session_end", "session" to s.id, "reason" to reason)

    /**
     * A text message from the receiver. Only `entry` pushes mean anything to the app
     * (docs/SERVER_API.md); anything else, including malformed JSON, is null and ignored.
     */
    fun parseServerMessage(text: String): EntryPush? = runCatching {
        val o = JSONObject(text)
        if (o.optString("type") != "entry") return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        EntryPush(id, o.str("kind"), o.str("status"), o.str("title"))
    }.getOrNull()

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    fun audio(f: AudioFrame): ByteArray =
        ByteBuffer.allocate(AUDIO_HEADER_LEN + f.adts.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(f.seq.toInt())
            .putLong(f.capturedAtMs)
            .put(f.adts)
            .array()

    fun json(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/** `{"type":"entry",…}`: a recording's entry changed on the receiver. Fetch it if you need more. */
class EntryPush(val id: String, val kind: String?, val status: String?, val title: String?)
