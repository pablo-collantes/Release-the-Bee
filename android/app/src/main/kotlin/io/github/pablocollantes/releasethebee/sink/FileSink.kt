package io.github.pablocollantes.releasethebee.sink

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records each session to `<dir>/<yyyy-MM-dd>/<HHmmss>_<device>.aac` on the phone.
 * Concatenated ADTS frames are a valid .aac file, so these play as-is. Pull them with
 * `adb pull /sdcard/Android/data/<applicationId>/files/recordings`.
 */
class FileSink(
    private val dir: File,
    private val onStatus: (String) -> Unit,
) : AudioSink {

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "sink-file") }
    private var out: FileOutputStream? = null

    override fun open() = onStatus("idle")

    override fun sessionStart(s: SessionInfo) = exec.execute {
        out?.close()
        val at = Date(s.startedAtMs)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(at)
        val time = SimpleDateFormat("HHmmss", Locale.US).format(at)
        val f = File(dir, "$day/${time}_${s.deviceIdHex.take(8)}.aac")
        f.parentFile?.mkdirs()
        out = FileOutputStream(f)
        onStatus("recording to $day/${f.name}")
    }

    override fun audio(f: AudioFrame) = exec.execute {
        runCatching { out?.write(f.adts) }.onFailure { onStatus("write failed: ${it.message}") }
    }

    override fun sessionEnd(s: SessionInfo, reason: String) = exec.execute {
        out?.close()
        out = null
        onStatus("idle")
    }

    override fun close() {
        exec.execute { runCatching { out?.close() }; out = null }
        exec.shutdown()
    }
}
