package io.github.pablocollantes.releasethebee.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The receiver's HTTP API (docs/SERVER_API.md), on the same host and port as its
 * WebSocket and behind the same bearer token. Calls block: run them off the main thread.
 */
class ReceiverApi(baseUrl: String, private val bearer: String?, private val client: OkHttpClient = shared) {

    /** The receiver answered, but not with success. */
    class HttpError(val code: Int) : IOException("HTTP $code")

    private val base: HttpUrl = requireNotNull(baseUrl.toHttpUrlOrNull()) { "not an http(s) URL" }

    /** `scheme://host:port`, for showing the user where the app is looking. */
    val origin: String = baseUrl

    /** Newest first. With [since] (the last response's `now`), only entries that changed after it. */
    fun entries(since: Long? = null): EntryPage {
        val url = base.newBuilder().addPathSegments("api/entries").apply {
            if (since != null) addQueryParameter("since", since.toString())
        }.build()
        return EntryJson.page(get(url))
    }

    /** The full entry: transcript, summary or command included. */
    fun entry(id: String): Entry = EntryJson.entry(get(entryUrl(id)))

    /** Queue the recording for processing again. */
    fun reprocess(id: String) {
        val url = entryUrl(id).newBuilder().addPathSegment("reprocess").build()
        call(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)))
    }

    /** Delete the recording and everything made from it. Already gone counts as done. */
    fun delete(id: String) {
        try {
            call(Request.Builder().url(entryUrl(id)).delete())
        } catch (e: HttpError) {
            if (e.code != 404) throw e
        }
    }

    /** Change a command's user fields (`done`, `when`, `text`, `note`); returns the entry. */
    fun patchCommand(id: String, fields: JSONObject): Entry {
        val body = JSONObject().put("command", fields).toString().toRequestBody(JSON)
        return EntryJson.entry(call(Request.Builder().url(entryUrl(id)).patch(body)))
    }

    /** The words whisper and the summaries are primed with, oldest first. */
    fun words(): List<String> = wordList(get(wordsUrl()))

    /** Replace the word list; returns it as the receiver kept it (trimmed, each once). */
    fun putWords(words: List<String>): List<String> {
        val body = JSONObject().put("words", JSONArray(words)).toString().toRequestBody(JSON)
        return wordList(call(Request.Builder().url(wordsUrl()).put(body)))
    }

    private fun wordsUrl() = base.newBuilder().addPathSegments("api/words").build()

    private fun wordList(json: String): List<String> {
        val a = JSONObject(json).optJSONArray("words") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    /** The recording as M4A; supports Range, so a player can seek. Needs [authHeaders]. */
    fun audioUrl(id: String): String = entryUrl(id).newBuilder().addPathSegment("audio").build().toString()

    fun authHeaders(): Map<String, String> =
        if (bearer.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $bearer")

    private fun entryUrl(id: String) = base.newBuilder().addPathSegments("api/entries").addPathSegment(id).build()

    private fun get(url: HttpUrl): String = call(Request.Builder().url(url).get())

    private fun call(b: Request.Builder): String {
        authHeaders().forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            return r.body?.string() ?: ""
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** One client for the app's HTTP calls, so connections are pooled and reused. */
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * The HTTP origin of a receiver, from the WebSocket URL the app streams to:
         * `ws://h:p/…` → `http://h:p`, `wss://h/…` → `https://h`. Null if it isn't a ws(s) URL.
         */
        fun httpBase(wsUrl: String): String? {
            val u = wsUrl.trim()
            val scheme = u.substringBefore("://", "").lowercase()
            val http = when (scheme) {
                "ws" -> "http"
                "wss" -> "https"
                else -> return null
            }
            val authority = u.substringAfter("://")
                .takeWhile { it != '/' && it != '?' && it != '#' }
                .substringAfterLast('@')                    // drop any user:password@
            if (authority.isEmpty()) return null
            val base = "$http://$authority"
            return if (base.toHttpUrlOrNull() != null) base else null
        }
    }
}
