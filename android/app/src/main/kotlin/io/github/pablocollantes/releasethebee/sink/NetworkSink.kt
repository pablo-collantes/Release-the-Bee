package io.github.pablocollantes.releasethebee.sink

import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Base for sinks that send over a connection that can go away: a bounded queue drained
 * by one worker thread, which reconnects with backoff (1 s → 60 s) when the receiver is
 * unreachable. While it's down, audio piles up in memory; past [maxBufferedFrames] the
 * oldest audio is dropped so a long outage can't exhaust memory.
 */
abstract class NetworkSink(
    private val label: String,
    private val onStatus: (String) -> Unit,
    private val maxBufferedFrames: Int = 8 * 60 * 15,     // ~15 min of audio, ~3.6 MB
) : AudioSink {

    protected sealed interface Item {
        class Start(val s: SessionInfo) : Item
        class Audio(val f: AudioFrame) : Item
        class End(val s: SessionInfo, val reason: String, val discard: Boolean) : Item
    }

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var audioQueued = 0
    private var dropped = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    /**
     * Sessions the receiver currently knows about, outermost first, re-announced after a
     * reconnect: none, one, or a parent with the child (command) recorded inside it.
     * Only the worker thread touches it.
     */
    private val open = ArrayList<SessionInfo>()

    /** Open the connection; throw on failure. */
    protected abstract fun connect()

    /** Called right after each successful [connect], with the sessions still open, parent first. */
    protected abstract fun greet(open: List<SessionInfo>)

    /** Send one item; throw if the connection is gone. */
    protected abstract fun send(item: Item)

    protected abstract fun disconnect()

    override fun open() {
        running = true
        worker = thread(name = "sink-$label", isDaemon = true) { loop() }
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
    }

    override fun sessionStart(s: SessionInfo) = offer(Item.Start(s))
    override fun audio(f: AudioFrame) = offer(Item.Audio(f))
    override fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean) = offer(Item.End(s, reason, discard))

    /** Frames currently waiting to be sent. */
    fun backlog(): Int = synchronized(lock) { audioQueued }

    private fun offer(item: Item) = synchronized(lock) {
        queue.addLast(item)
        if (item is Item.Audio && ++audioQueued > maxBufferedFrames) {
            queue.removeAt(queue.indexOfFirst { it is Item.Audio })
            audioQueued--
            dropped++
        }
        lock.notifyAll()
    }

    private fun take(timeoutMs: Long): Item? = synchronized(lock) {
        if (queue.isEmpty()) lock.wait(timeoutMs)
        queue.removeFirstOrNull()?.also { if (it is Item.Audio) audioQueued-- }
    }

    private fun putBack(item: Item) = synchronized(lock) {
        queue.addFirst(item)
        if (item is Item.Audio) audioQueued++
    }

    /** Keep [open] in step with what the receiver has been told. */
    private fun track(item: Item) {
        when (item) {
            is Item.Start -> {
                if (item.s.parentId == null) open.clear()       // a new top-level session replaces everything
                else open.removeAll { it.parentId != null }      // at most one level of nesting
                open += item.s
            }
            is Item.End -> {
                val i = open.indexOfFirst { it.id == item.s.id }
                if (i >= 0) while (open.size > i) open.removeAt(open.lastIndex)   // ending a parent ends its child
            }
            is Item.Audio -> {}
        }
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            try {
                onStatus("connecting")
                connect()
                greet(open.toList())
                attempt = 0
                onStatus("connected")
                while (running) {
                    val item = take(1000) ?: continue
                    try {
                        send(item)
                    } catch (e: Exception) {
                        putBack(item)
                        throw e
                    }
                    track(item)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                runCatching { disconnect() }
                if (!running) break
                val delay = (1000L shl min(attempt++, 6)).coerceAtMost(60_000)
                val lost = synchronized(lock) { if (dropped > 0) ", $dropped dropped" else "" }
                onStatus("unreachable (${e.message ?: e.javaClass.simpleName}); retry in ${delay / 1000}s, ${backlog()} frames waiting$lost")
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        runCatching { disconnect() }
        onStatus("closed")
    }
}
