package io.github.pablocollantes.releasethebee.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.sink.EntryPush
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-process status shared by the service (writer) and the UI (reader). Never holds secrets. */
object AppStatus {

    data class Snapshot(
        val serviceRunning: Boolean = false,
        val link: RtbLink.State = RtbLink.State.STOPPED,
        val linkDetail: String = "",
        val sinks: Map<String, String> = emptyMap(),
        val sessionStartedAt: Long? = null,
        val frames: Long = 0,
    )

    private const val TAG = "releasethebee"
    private const val MAX_LOG = 200

    @Volatile var snapshot = Snapshot()
        private set

    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val entryListeners = CopyOnWriteArrayList<(EntryPush) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { snapshot = f(snapshot) }
        notifyListeners()
    }

    fun log(line: String) {
        Log.i(TAG, line)
        synchronized(logLines) {
            logLines.addLast("${clock.format(Date())}  $line")
            while (logLines.size > MAX_LOG) logLines.removeFirst()
        }
        notifyListeners()
    }

    fun logs(): List<String> = synchronized(logLines) { logLines.toList() }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /**
     * The receiver pushed an entry change over the WebSocket. Delivered on the main thread
     * to whichever screens are listening; with none open, it costs nothing.
     */
    fun entryPushed(e: EntryPush) {
        if (entryListeners.isEmpty()) return
        main.post { entryListeners.forEach { it(e) } }
    }

    fun addEntryListener(l: (EntryPush) -> Unit) = entryListeners.add(l)
    fun removeEntryListener(l: (EntryPush) -> Unit) = entryListeners.remove(l)

    private fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }
}
