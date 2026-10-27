package io.github.pablocollantes.releasethebee.rtb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class SealTokenTest {

    // A made-up token: right shape, random contents. Not any real device's.
    private val deviceId = ByteArray(8) { (0x10 + it).toByte() }
    private val ctrKey = ByteArray(16) { (0xA0 + it).toByte() }
    private val raw = "SKV1".toByteArray() + deviceId + ctrKey + Random(7).nextBytes(80)

    @Test fun `splits the fields`() {
        val t = SealToken.fromBytes(raw)
        assertArrayEquals(deviceId, t.deviceId)
        assertArrayEquals(ctrKey, t.ctrKey())
        assertEquals("1011121314151617", t.deviceIdHex)
        assertArrayEquals(raw, t.bytes())
    }

    @Test fun `accepts the common pasted forms`() {
        val hex = raw.toHex()
        val forms = listOf(
            hex,
            hex.uppercase(),
            "18c0$hex",                                             // parser's "full wire" line
            hex.chunked(2).joinToString(" "),                       // hexdump
            hex.chunked(2).joinToString(":"),
            hex.chunked(64).joinToString("\n"),                     // wrapped
            raw.joinToString(", ") { "0x%02x".format(it) },         // C array
            Base64.getEncoder().encodeToString(raw),
        )
        for (f in forms) assertArrayEquals(f, raw, SealToken.parse(f).bytes())
    }

    @Test fun `imports binary and text files`() {
        assertArrayEquals(raw, SealToken.fromFile(raw).bytes())
        assertArrayEquals(raw, SealToken.fromFile(byteArrayOf(0x18, 0xC0.toByte()) + raw).bytes())
        assertArrayEquals(raw, SealToken.fromFile((raw.toHex() + "\n").toByteArray()).bytes())
    }

    @Test fun `rejects wrong length and magic`() {
        assertThrows(IllegalArgumentException::class.java) { SealToken.fromBytes(raw.copyOf(107)) }
        assertThrows(IllegalArgumentException::class.java) { SealToken.fromBytes(ByteArray(108)) }
        assertThrows(IllegalArgumentException::class.java) { SealToken.parse(ctrKey.toHex()) }   // a bare key
        assertThrows(IllegalArgumentException::class.java) { SealToken.parse("not a token") }
    }

    @Test fun `toString never leaks key material`() {
        val s = SealToken.fromBytes(raw).toString()
        assertFalse(s.contains(ctrKey.toHex()))
        assertFalse(s.contains(raw.copyOfRange(28, 40).toHex()))
    }
}
