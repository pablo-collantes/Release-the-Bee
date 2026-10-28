package io.github.pablocollantes.releasethebee.sink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkSinkTest {

    /** A receiver that is down until [up] is released, then records what arrives. */
    private class FakeSink(max: Int, private val up: CountDownLatch, private val expected: CountDownLatch) :
        NetworkSink("fake", {}, max) {
        val got = CopyOnWriteArrayList<String>()
        val greetings = CopyOnWriteArrayList<String?>()
        @Volatile var failNextAudio = false
        override fun connect() { if (up.count > 0) throw IOException("down") }
        val reopened = CopyOnWriteArrayList<List<String>>()
        override fun greet(open: List<SessionInfo>) {
            greetings += open.firstOrNull()?.id
            reopened += open.map { it.id }
        }
        override fun send(item: Item) {
            if (item is Item.Audio && failNextAudio) { failNextAudio = false; throw IOException("drop") }
            got += when (item) {
                is Item.Start -> "start:${item.s.id}"
                is Item.Audio -> "audio:${item.f.seq}"
                is Item.End -> "end:${item.s.id}"
            }
            expected.countDown()
        }
        override fun disconnect() {}
    }

    private fun frame(seq: Long) = AudioFrame(seq, 0, byteArrayOf(seq.toByte()))

    @Test fun `buffers while down and drops the oldest audio past the cap`() {
        val up = CountDownLatch(1)
        val expected = CountDownLatch(5)                 // start + 3 newest audio + end
        val sink = FakeSink(max = 3, up, expected)
        val s = SessionInfo("s1", "dev", 0)
        sink.sessionStart(s)
        (1L..5L).forEach { sink.audio(frame(it)) }
        sink.sessionEnd(s, "done")
        assertEquals(3, sink.backlog())
        sink.open()
        up.countDown()
        assertEquals(true, expected.await(5, TimeUnit.SECONDS))
        sink.close()
        assertEquals(listOf("start:s1", "audio:3", "audio:4", "audio:5", "end:s1"), sink.got)
    }

    @Test fun `re-announces the open session after a reconnect`() {
        val up = CountDownLatch(0)
        val expected = CountDownLatch(3)
        val sink = FakeSink(max = 100, up, expected)
        val s = SessionInfo("s2", "dev", 0)
        sink.failNextAudio = true
        sink.sessionStart(s)
        sink.audio(frame(1))
        sink.audio(frame(2))
        sink.open()
        assertEquals(true, expected.await(10, TimeUnit.SECONDS))
        sink.close()
        assertEquals(listOf("start:s2", "audio:1", "audio:2"), sink.got)  // frame 1 retried, not lost
        assertEquals(listOf(null, "s2"), sink.greetings.take(2))          // second hello resumes s2
    }

    @Test fun `re-announces a nested command after its parent, and only the parent once it ends`() {
        val sink = FakeSink(max = 100, CountDownLatch(0), CountDownLatch(0))
        val parent = SessionInfo("p", "dev", 0)
        val child = SessionInfo("c", "dev", 1, SessionInfo.Kind.COMMAND, SessionInfo.Trigger.DOUBLE_PRESS, parentId = "p")
        sink.failNextAudio = true                 // the connection drops while the command is open
        sink.sessionStart(parent)
        sink.sessionStart(child)
        sink.audio(frame(1))
        sink.sessionEnd(child, "done")
        sink.audio(frame(2))
        sink.open()
        awaitSize(sink.got, 5)
        sink.failNextAudio = true                 // and again, after the command has ended
        sink.audio(frame(3))
        awaitSize(sink.got, 6)
        sink.close()
        assertEquals(listOf("start:p", "start:c", "audio:1", "end:c", "audio:2", "audio:3"), sink.got)
        assertEquals(listOf(emptyList(), listOf("p", "c"), listOf("p")), sink.reopened.toList())
    }

    private fun awaitSize(list: List<*>, n: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (list.size < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(n, list.size)
    }
}
