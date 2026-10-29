package io.github.pablocollantes.releasethebee.data

import android.content.Context
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.service.AppStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The copies [io.github.pablocollantes.releasethebee.sink.FileSink] keeps on the phone
 * ("Also keep a copy on this phone"). Each is deleted a week after it was recorded, once
 * the receiver has transcribed it: by then the receiver has the audio and everything made
 * from it. A copy the receiver never got is kept.
 */
object PhoneCopies {

    const val KEEP_MS = 7 * 24 * 3600 * 1000L
    private const val PRUNE_EVERY_MS = 12 * 3600 * 1000L

    /** `yyyy-MM-dd/HHmmss_<tag>.aac`, [modified] its last write. */
    class Copy(val path: String, val modified: Long)

    private val NAME = Regex("""^(\d{4}-\d{2}-\d{2})/(\d{6})_([0-9A-Za-z-]{1,8})\.aac$""")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HHmmss")
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "phone-copies").apply { isDaemon = true } }

    /**
     * The recording a copy belongs to: by the session id in its name, or (copies from
     * before 0.4, named after the device) by the second it started.
     */
    fun match(c: Copy, entries: List<Entry>, zone: ZoneId = ZoneId.systemDefault()): Entry? {
        val m = NAME.matchEntire(c.path) ?: return null
        val (day, time, tag) = m.destructured
        return entries.firstOrNull { it.id.startsWith(tag) } ?: entries.firstOrNull {
            val t = Instant.ofEpochMilli(it.startedAt).atZone(zone)
            DAY.format(t) == day && TIME.format(t) == time
        }
    }

    /** Copies older than [KEEP_MS] whose recording the receiver has finished with. */
    fun expired(copies: List<Copy>, entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Copy> =
        copies.filter { now - it.modified > KEEP_MS && match(it, entries, zone)?.status == Entry.Status.DONE }

    /** Prune at most every 12 hours, off the main thread. */
    fun pruneSometimes(ctx: Context, entries: List<Entry>) {
        val prefs = ctx.getSharedPreferences("phone_copies", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("pruned_at", 0) < PRUNE_EVERY_MS) return
        prefs.edit().putLong("pruned_at", now).apply()
        val dir = ctx.getExternalFilesDir("recordings") ?: return
        val snapshot = entries.toList()
        exec.execute {
            val gone = expired(list(dir), snapshot, now).count { File(dir, it.path).delete() }
            dropEmptyDays(dir)
            if (gone > 0) AppStatus.log("deleted $gone phone ${if (gone == 1) "copy" else "copies"} older than a week, already transcribed")
        }
    }

    /** The user deleted these recordings: their copies go too. Blocking. */
    fun delete(ctx: Context, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val dir = ctx.getExternalFilesDir("recordings") ?: return
        list(dir).filter { c -> match(c, entries)?.let { e -> entries.any { it.id == e.id } } == true }
            .forEach { File(dir, it.path).delete() }
        dropEmptyDays(dir)
    }

    private fun list(dir: File): List<Copy> =
        dir.listFiles()?.filter { it.isDirectory }.orEmpty().flatMap { day ->
            day.listFiles()?.filter { it.isFile }.orEmpty().map { Copy("${day.name}/${it.name}", it.lastModified()) }
        }

    private fun dropEmptyDays(dir: File) {
        dir.listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
    }
}
