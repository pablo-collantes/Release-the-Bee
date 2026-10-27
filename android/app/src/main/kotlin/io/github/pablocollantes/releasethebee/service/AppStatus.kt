package io.github.pablocollantes.releasethebee.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.pablocollantes.releasethebee.rtb.RtbLink
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

    private fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }
}
