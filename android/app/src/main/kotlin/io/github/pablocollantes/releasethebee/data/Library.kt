package io.github.pablocollantes.releasethebee.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.api.EntryIndex
import io.github.pablocollantes.releasethebee.api.EntryJson
import io.github.pablocollantes.releasethebee.api.EntryPage
import io.github.pablocollantes.releasethebee.api.ReceiverApi
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.service.ReminderAlarms
import io.github.pablocollantes.releasethebee.service.RtbService
import io.github.pablocollantes.releasethebee.sink.EntryPush
import io.github.pablocollantes.releasethebee.ui.Receiver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Everything the receiver has made, as the app knows it: recordings, and the notes and
 * reminders read out of their commands. One copy for the whole app. The screens read it;
 * the service keeps it current while they're closed (receiver pushes, and a catch-up
 * fetch when the receiver connection comes back), so reminders get their alarms with no
 * screen open.
 *
 * It's kept on disk between runs: the app opens on the last list it had, and fetches only
 * what changed since (`?since=`). Changes the user makes to a command (a reminder checked
 * off, a new time) apply here at once and reach the receiver with the next fetch, so they
 * work with the receiver away too.
 *
 * Main thread only. Network and disk run on their own threads and report back here.
 */
object Library {

    val index = EntryIndex()

    /** Which receiver [index] came from. Another URL means a full fetch, not `?since=`. */
    var origin: String? = null
        private set
    var source: Receiver.Source? = null
        private set
    /** Why the last fetch failed; null when it worked. */
    var problem: Receiver.Problem? = null
        private set
    /** The last fetch reached the receiver. */
    var fetchedOk = false
        private set
    var loading = false
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor { Thread(it, "library-net").apply { isDaemon = true } }
    private val disk = Executors.newSingleThreadExecutor { Thread(it, "library-disk").apply { isDaemon = true } }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** Command changes the receiver hasn't confirmed yet: id → fields to PATCH. */
    private val pending = LinkedHashMap<String, JSONObject>()
    private var loaded = false
    private var again = false
    private val refreshSoon = Runnable { refresh(app) }

    /** Read last run's copy from disk, once per process. Small: fine on the main thread. */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        app = ctx.applicationContext
        val f = file()
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i -> runCatching { EntryJson.entry(arr.getJSONObject(i)) }.getOrNull() }
            index.restore(list, if (o.isNull("cursor")) null else o.optLong("cursor"))
            origin = if (o.isNull("origin")) null else o.optString("origin")
            o.optJSONObject("pending")?.let { p -> p.keys().forEach { pending[it] = p.getJSONObject(it) } }
        }.onFailure { AppStatus.log("couldn't read the saved recordings list (${it.javaClass.simpleName}); fetching it again") }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    // ------------------------------------------------------------------ fetching

    /** Fetch what changed (everything, the first time), after sending any pending changes. */
    fun refresh(ctx: Context) {
        load(ctx)
        main.removeCallbacks(refreshSoon)
        if (loading) {
            again = true
            return
        }
        loading = true
        changed(save = false)
        val from = origin
        val cursor = index.cursor
        val outgoing = LinkedHashMap(pending)
        net.execute {
            val src = Receiver.source(app)
            val ready = src as? Receiver.Source.Ready
            val since = if (ready != null && ready.api.origin == from) cursor else null
            val result = ready?.let { r ->
                runCatching {
                    val sent = outgoing.mapNotNull { (id, fields) -> sendPatch(r.api, id, fields) }
                    r.api.entries(since) to sent
                }
            }
            main.post { fetched(src, since, outgoing, result) }
        }
    }

    /** One fetch for a burst of pushes. */
    fun refreshLater(ctx: Context) {
        load(ctx)
        main.removeCallbacks(refreshSoon)
        main.postDelayed(refreshSoon, 800)
    }

    /** The id if the receiver took the change (or the entry is gone, so it never will). */
    private fun sendPatch(api: ReceiverApi, id: String, fields: JSONObject): String? = try {
        api.patchCommand(id, fields)
        id
    } catch (e: ReceiverApi.HttpError) {
        if (e.code in listOf(400, 404, 409)) id else null
    }

    private fun fetched(src: Receiver.Source, since: Long?, sent: Map<String, JSONObject>, result: Result<Pair<EntryPage, List<String>>>?) {
        loading = false
        source = src
        when {
            result == null -> problem = Receiver.problem(src)
            result.isSuccess -> {
                val (page, done) = result.getOrThrow()
                // Changed again while that was in flight: still pending.
                done.forEach { id -> if (pending[id] === sent[id]) pending.remove(id) }
                if (since == null) {
                    index.replaceAll(page)
                    origin = (src as Receiver.Source.Ready).api.origin
                } else {
                    index.merge(page)
                }
                pending.forEach { (id, fields) -> applyLocally(id, fields) }
                problem = null
                fetchedOk = true
            }
            else -> {
                problem = Receiver.problem(src, result.exceptionOrNull())
                fetchedOk = false
            }
        }
        changed(save = result?.isSuccess == true)
        if (result?.isSuccess == true) {
            ReminderAlarms.sync(app)
            PhoneCopies.pruneSometimes(app, index.entries)
        }
        if (again) {
            again = false
            refresh(app)
        }
    }

    /** The receiver pushed a change over the WebSocket. */
    fun onPush(ctx: Context, p: EntryPush) {
        load(ctx)
        if (p.status == "deleted") {
            if (index.remove(listOf(p.id))) {
                changed(save = true)
                ReminderAlarms.sync(app)
            }
            return
        }
        val known = index.applyPush(p)
        if (known) changed(save = false)
        // A push carries status and title only. Fetch the rest (duration, preview, the
        // command) when the entry is new, or has just ended or finished.
        val status = Entry.Status.of(p.status)
        if (!known || status == Entry.Status.QUEUED || status.finished) refreshLater(ctx)
    }

    // ------------------------------------------------------------------ changes

    /**
     * Delete recordings: from the receiver, this phone's copies, and the list. One that's
     * still recording stops first, and the receiver throws it away instead of transcribing
     * it. [done] gets how many the receiver couldn't delete (they come back in the list).
     */
    fun delete(ctx: Context, ids: Collection<String>, done: (failed: Int) -> Unit) {
        load(ctx)
        val gone = ids.mapNotNull { index[it] }
        val live = AppStatus.snapshot.live.toSet()
        ids.filter { it in live }.forEach { RtbService.discard(app, it) }
        index.remove(ids)
        ids.forEach { pending.remove(it) }
        changed(save = true)
        ReminderAlarms.sync(app)
        disk.execute { PhoneCopies.delete(app, gone) }
        val remote = ids.filter { it !in live }       // those go through the stream, as it ends
        if (remote.isEmpty()) return done(0)
        net.execute {
            val api = (Receiver.source(app) as? Receiver.Source.Ready)?.api
            val failed = if (api == null) remote.size else remote.count { id -> runCatching { api.delete(id) }.isFailure }
            main.post {
                if (failed > 0) refresh(app)
                done(failed)
            }
        }
    }

    fun setDone(ctx: Context, id: String, done: Boolean) = patch(ctx, id, JSONObject().put("done", done))

    /** [whenIso]: ISO 8601 with offset, or null for no time. */
    fun setWhen(ctx: Context, id: String, whenIso: String?) =
        patch(ctx, id, JSONObject().put("when", whenIso ?: JSONObject.NULL))

    private fun patch(ctx: Context, id: String, fields: JSONObject) {
        load(ctx)
        if (index[id]?.command == null) return
        val merged = JSONObject(pending[id]?.toString() ?: "{}")     // a new object: see fetched()
        fields.keys().forEach { merged.put(it, fields.get(it)) }
        pending[id] = merged
        applyLocally(id, fields)
        changed(save = true)
        ReminderAlarms.sync(app)
        refresh(app)
    }

    private fun applyLocally(id: String, fields: JSONObject) {
        val e = index[id] ?: return
        var c = e.command ?: return
        if (fields.has("done")) c = c.copy(done = fields.optBoolean("done"))
        if (fields.has("when")) c = c.copy(whenIso = if (fields.isNull("when")) null else fields.optString("when"))
        index.upsert(e.copy(command = c))
    }

    /** Something the screens show changed outside the list (a reminder's time confirmed). */
    fun touch() = changed(save = false)

    /** Run [f] on the disk thread once everything queued before it is written. */
    fun afterWrites(f: () -> Unit) = disk.execute(f)

    // ------------------------------------------------------------------ disk

    private fun changed(save: Boolean) {
        if (save) save()
        listeners.forEach { it() }
    }

    private fun save() {
        val o = JSONObject()
            .put("version", 1)
            .put("origin", origin ?: JSONObject.NULL)
            .put("cursor", index.cursor ?: JSONObject.NULL)
            .put("entries", JSONArray().apply { index.entries.forEach { put(EntryJson.light(it)) } })
            .put("pending", JSONObject().apply { pending.forEach { (id, f) -> put(id, JSONObject(f.toString())) } })
        val text = o.toString()
        val f = file()
        disk.execute {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(f)
            }
        }
    }

    private fun file() = File(app.filesDir, "library.json")
}
