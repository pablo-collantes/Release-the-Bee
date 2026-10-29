package io.github.pablocollantes.releasethebee.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * In-process status shared by the service (writer) and the UI (reader). Never holds secrets.
 * The log also goes to `files/log.txt` (and `log.1.txt` before it), so a test on the RTB
 * can be read back later: `adb shell run-as <applicationId> cat files/log.txt`.
 */
object AppStatus {

    data class Snapshot(
        val serviceRunning: Boolean = false,
        val link: RtbLink.State = RtbLink.State.STOPPED,
        val linkDetail: String = "",
        val sinks: Map<String, String> = emptyMap(),
        val sessionStartedAt: Long? = null,
        val frames: Long = 0,
        /** A command is recording (on its own, or inside a summary). */
        val commandOpen: Boolean = false,
        /** Ids of the recordings open now, outermost first. */
        val live: List<String> = emptyList(),
        /** The RTB's answer to the last request from the light tests. */
        val reply: String = "",
        /** The RTB's battery in percent, as it last said (kept while it's away), and whether it's charging. */
        val battery: Int? = null,
        val charging: Boolean? = null,
        /** When [battery] was last heard, for "as of" once the RTB is out of reach. */
        val batteryAt: Long? = null,
    )

    private const val TAG = "releasethebee"
    private const val MAX_LOG = 200
    private const val MAX_FILE = 512 * 1024L

    @Volatile var snapshot = Snapshot()
        private set

    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val disk = Executors.newSingleThreadExecutor { Thread(it, "log").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "log.txt")
    }

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { snapshot = f(snapshot) }
        notifyListeners()
    }

    fun log(line: String) {
        Log.i(TAG, line)
        val now = Date()
        synchronized(logLines) {
            logLines.addLast("${clock.format(now)}  $line")
            while (logLines.size > MAX_LOG) logLines.removeFirst()
        }
        file?.let { f ->
            val stamped = "${synchronized(stamp) { stamp.format(now) }}  $line\n"
            disk.execute {
                runCatching {
                    if (f.length() > MAX_FILE) f.renameTo(File(f.parentFile, "log.1.txt"))
                    f.appendText(stamped)
                }
            }
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
