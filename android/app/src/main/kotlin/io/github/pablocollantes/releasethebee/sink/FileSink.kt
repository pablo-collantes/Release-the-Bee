package io.github.pablocollantes.releasethebee.sink

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records each session to `<dir>/<yyyy-MM-dd>/<HHmmss>_<session8>.aac` on the phone, the
 * same name the receiver gives it, so a copy can be matched to its entry
 * ([io.github.pablocollantes.releasethebee.data.PhoneCopies]). Concatenated ADTS frames are
 * a valid .aac file, so these play as-is. Pull them with
 * `adb pull /sdcard/Android/data/<applicationId>/files/recordings`.
 *
 * A command recorded inside a summary gets its own file; the summary's file picks up
 * again (appending) when the command ends.
 */
class FileSink(
    private val dir: File,
    private val onStatus: (String) -> Unit,
) : AudioSink {

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "sink-file") }
    private val open = ArrayList<Pair<String, File>>()     // session id → file, outermost first
    private var out: FileOutputStream? = null

    override fun open() = onStatus("idle")

    override fun sessionStart(s: SessionInfo) = exec.execute {
        if (s.parentId == null) open.clear()
        val at = Date(s.startedAtMs)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(at)
        val time = SimpleDateFormat("HHmmss", Locale.US).format(at)
        val f = File(dir, "$day/${time}_${s.id.take(8)}.aac")
        f.parentFile?.mkdirs()
        open += s.id to f
        switchTo(f, append = false)
        onStatus("recording to $day/${f.name}")
    }

    override fun audio(f: AudioFrame) = exec.execute {
        runCatching { out?.write(f.adts) }.onFailure { onStatus("write failed: ${it.message}") }
    }

    override fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean) = exec.execute {
        val i = open.indexOfFirst { it.first == s.id }
        if (i < 0) return@execute
        runCatching { out?.close() }
        out = null
        if (discard) open[i].second.delete()
        open.removeAt(i)
        // Back to the summary a command was recorded in, if it's still going.
        val parent = open.lastOrNull()
        if (parent != null) switchTo(parent.second, append = true) else onStatus("idle")
    }

    private fun switchTo(f: File, append: Boolean) {
        runCatching { out?.close() }
        out = runCatching { FileOutputStream(f, append) }
            .onFailure { onStatus("can't write ${f.name}: ${it.message}") }
            .getOrNull()
    }

    override fun close() {
        exec.execute { runCatching { out?.close() }; out = null }
        exec.shutdown()
    }
}
