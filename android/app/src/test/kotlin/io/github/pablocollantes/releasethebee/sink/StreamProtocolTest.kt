package io.github.pablocollantes.releasethebee.sink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
            """{"type":"session_start","session":"abc","device":"0011223344556677","started_at":42,"resumed":true}""",
            StreamProtocol.sessionStart(s, resumed = true),
        )
        assertEquals(
            """{"type":"session_end","session":"abc","reason":"link \"lost\"\n"}""",
            StreamProtocol.sessionEnd(s, "link \"lost\"\n"),
        )
    }
}
