package io.github.pablocollantes.releasethebee.sink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class StreamProtocolTest {

    @Test fun `audio frames carry seq and capture time ahead of the AAC`() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 1, 2, 3)
        val b = StreamProtocol.audio(AudioFrame(0xFFFFFFFEL, 1_700_000_000_123L, adts))
        val buf = ByteBuffer.wrap(b)
        assertEquals(0xFFFFFFFEL, buf.int.toLong() and 0xFFFFFFFFL)
        assertEquals(1_700_000_000_123L, buf.long)
        assertArrayEquals(adts, b.copyOfRange(StreamProtocol.AUDIO_HEADER_LEN, b.size))
    }

    @Test fun `control messages are JSON`() {
        val s = SessionInfo("abc", "0011223344556677", 42)
        assertEquals(
            """{"type":"session_start","session":"abc","device":"0011223344556677","started_at":42,"resumed":true,""" +
                """"kind":"summary","trigger":"button","parent":null}""",
            StreamProtocol.sessionStart(s, resumed = true),
        )
        assertEquals(
            """{"type":"session_end","session":"abc","reason":"link \"lost\"\n"}""",
            StreamProtocol.sessionEnd(s, "link \"lost\"\n"),
        )
    }

    @Test fun `session_start carries kind, trigger and parent for a nested command`() {
        val c = SessionInfo("cmd", "0011223344556677", 43, SessionInfo.Kind.COMMAND, SessionInfo.Trigger.HOLD, parentId = "abc")
        assertEquals(
            """{"type":"session_start","session":"cmd","device":"0011223344556677","started_at":43,"resumed":false,""" +
                """"kind":"command","trigger":"hold","parent":"abc"}""",
            StreamProtocol.sessionStart(c, resumed = false),
        )
        val unknownTrigger = SessionInfo("x", "d", 1, trigger = null)
        assertTrue(StreamProtocol.sessionStart(unknownTrigger, resumed = false).contains(""""trigger":null"""))
        assertEquals("double_press", SessionInfo.Trigger.DOUBLE_PRESS.wire)
        assertEquals("app", SessionInfo.Trigger.APP.wire)
    }

    @Test fun `hello says null device until a ctrKey has bound`() {
        assertTrue(StreamProtocol.hello("app/1", null).contains(""""device":null"""))
        assertTrue(StreamProtocol.hello("app/1", "0011223344556677").contains(""""device":"0011223344556677""""))
    }

    @Test fun `parses entry pushes and ignores everything else`() {
        val p = StreamProtocol.parseServerMessage(
            """{"type":"entry","id":"5f0c2a91","kind":"summary","status":"done","title":"Planning \"sync\""}""",
        )!!
        assertEquals("5f0c2a91", p.id)
        assertEquals("summary", p.kind)
        assertEquals("done", p.status)
        assertEquals("Planning \"sync\"", p.title)
        val untitled = StreamProtocol.parseServerMessage("""{"type":"entry","id":"a","status":"queued","title":null}""")!!
        assertNull(untitled.title)
        assertNull(untitled.kind)
        assertNull(StreamProtocol.parseServerMessage("""{"type":"pong"}"""))
        assertNull(StreamProtocol.parseServerMessage("""{"type":"entry"}"""))          // no id
        assertNull(StreamProtocol.parseServerMessage("""{"hello":1}"""))
        assertNull(StreamProtocol.parseServerMessage("not json"))
        assertNull(StreamProtocol.parseServerMessage("[1,2]"))
    }
}
