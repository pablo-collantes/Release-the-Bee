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
        class End(val s: SessionInfo, val reason: String) : Item
    }

    private val lock = Object()
    private val queue = ArrayDeque<Item>()
    private var audioQueued = 0
    private var dropped = 0L
    @Volatile private var running = false
    private var worker: Thread? = null

    /** The session the receiver currently knows about, re-announced after a reconnect. */
    private var current: SessionInfo? = null

    /** Open the connection; throw on failure. */
    protected abstract fun connect()

    /** Called right after each successful [connect]. */
    protected abstract fun greet(resume: SessionInfo?)

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
    override fun sessionEnd(s: SessionInfo, reason: String) = offer(Item.End(s, reason))

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

    private fun loop() {
        var attempt = 0
        while (running) {
            try {
                onStatus("connecting")
                connect()
                greet(current)
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
                    when (item) {
                        is Item.Start -> current = item.s
                        is Item.End -> current = null
                        is Item.Audio -> {}
                    }
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
