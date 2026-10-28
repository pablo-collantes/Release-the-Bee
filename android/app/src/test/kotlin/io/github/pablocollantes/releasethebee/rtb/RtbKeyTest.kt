package io.github.pablocollantes.releasethebee.rtb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class RtbKeyTest {

    // Made-up keys: right shapes, random contents. Not any real device's.
    private val ctrKey = Random(11).nextBytes(16)
    private val deviceId = Random(12).nextBytes(8)
    private val token = "SKV1".toByteArray() + deviceId + Random(13).nextBytes(16) + Random(14).nextBytes(80)

    private fun ctr(k: RtbKey) = (k as RtbKey.CtrOnly).ctrKey()

    @Test fun `a ctrKey pasted as hex in any common spacing`() {
        val hex = ctrKey.toHex()
        val forms = listOf(
            hex,
            hex.uppercase(),
            "  $hex\n",
            hex.chunked(2).joinToString(" "),
            hex.chunked(2).joinToString(":"),
            hex.chunked(8).joinToString("-"),
            ctrKey.joinToString(", ") { "0x%02x".format(it) },
        )
        for (f in forms) {
            val k = RtbKey.parse(f)
            assertTrue(f, k is RtbKey.CtrOnly)
            assertArrayEquals(f, ctrKey, ctr(k))
            assertNull("a bare ctrKey doesn't name its RTB", k.deviceId)
            assertFalse(k.canSeal)
        }
    }

    @Test fun `a ctrKey pasted as base64, padded or not`() {
        val b64 = Base64.getEncoder().encodeToString(ctrKey)
        assertArrayEquals(ctrKey, ctr(RtbKey.parse(b64)))
        assertArrayEquals(ctrKey, ctr(RtbKey.parse(b64.trimEnd('='))))
        assertArrayEquals(ctrKey, ctr(RtbKey.parse(" $b64\n")))
    }

    @Test fun `a ctrKey from a file - raw 16 bytes or text`() {
        assertArrayEquals(ctrKey, ctr(RtbKey.fromFile(ctrKey)))
        assertArrayEquals(ctrKey, ctr(RtbKey.fromFile((ctrKey.toHex() + "\n").toByteArray())))
        assertArrayEquals(ctrKey, ctr(RtbKey.fromFile(Base64.getEncoder().encode(ctrKey))))
    }

    @Test fun `rejects keys of the wrong length`() {
        val bad = listOf(
            Random(1).nextBytes(15).toHex(),
            Random(2).nextBytes(17).toHex(),
            ctrKey.toHex().dropLast(1),                               // 31 hex characters
            Base64.getEncoder().encodeToString(Random(3).nextBytes(20)),
            token.toHex().dropLast(2),                                // a token one byte short
            "",
            "not a key",
        )
        for (b in bad) assertThrows(b, IllegalArgumentException::class.java) { RtbKey.parse(b) }
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromBytes(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromBytes(ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromFile(ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromFile(ByteArray(17)) }
        // 16 bytes of hex digits is half a key written as text, not a raw key.
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromFile(ctrKey.toHex().take(16).toByteArray()) }
        // 108 bytes that aren't a token.
        assertThrows(IllegalArgumentException::class.java) { RtbKey.fromBytes(ByteArray(108)) }
        // A message for 31 hex characters talks about hex, not about bytes.
        val msg = assertThrows(IllegalArgumentException::class.java) { RtbKey.parse(ctrKey.toHex().dropLast(1)) }.message!!
        assertTrue(msg, msg.contains("31 hex characters"))
    }

    @Test fun `the seal token path still works - pasted, wire form, base64 and file`() {
        val forms = listOf(
            token.toHex(),
            "18c0" + token.toHex(),
            token.toHex().chunked(64).joinToString("\n"),
            Base64.getEncoder().encodeToString(token),
        )
        for (f in forms) {
            val k = RtbKey.parse(f)
            assertTrue(f, k is RtbKey.Full)
            assertArrayEquals(token, (k as RtbKey.Full).token.bytes())
            assertArrayEquals(deviceId, k.deviceId)
            assertArrayEquals(token.copyOfRange(12, 28), k.ctrKey())
            assertTrue(k.canSeal)
        }
        assertTrue(RtbKey.fromFile(token) is RtbKey.Full)
        assertTrue(RtbKey.fromFile(byteArrayOf(0x18, 0xC0.toByte()) + token) is RtbKey.Full)
        assertTrue(RtbKey.fromFile((token.toHex() + "\n").toByteArray()) is RtbKey.Full)
        // SealToken alone still refuses a bare key.
        assertThrows(IllegalArgumentException::class.java) { SealToken.parse(ctrKey.toHex()) }
    }

    @Test fun `a token stored by 0_1 reads back as a full key`() {
        // 0.1 stored the raw 108 bytes; the new store reads the same bytes under the same name.
        val k = RtbKey.decode(token)
        assertTrue(k is RtbKey.Full)
        assertArrayEquals(token, k.encode())
        assertEquals(deviceId.toHex(), k.deviceIdHex)
    }

    @Test fun `stored ctrKeys round-trip, bound or not`() {
        val unbound = RtbKey.CtrOnly(ctrKey)
        val back = RtbKey.decode(unbound.encode())
        assertTrue(back is RtbKey.CtrOnly)
        assertArrayEquals(ctrKey, back.ctrKey())
        assertNull(back.deviceId)

        val bound = unbound.boundTo(deviceId)
        val back2 = RtbKey.decode(bound.encode())
        assertArrayEquals(ctrKey, back2.ctrKey())
        assertArrayEquals(deviceId, back2.deviceId)
        assertTrue(back2.sameKeyAs(unbound))
        assertNull((back2 as RtbKey.CtrOnly).unbound().deviceId)

        // The stored form never looks like a seal token, and a full token never looks like a ctrKey.
        assertFalse(String(bound.encode(), Charsets.ISO_8859_1).startsWith("SKV1"))
        assertFalse(RtbKey.decode(token).sameKeyAs(unbound))
        assertThrows(IllegalArgumentException::class.java) { RtbKey.decode("RTBK".toByteArray() + ByteArray(5)) }
    }

    @Test fun `binding copies, so callers can't change the key behind its back`() {
        val src = ctrKey.copyOf()
        val k = RtbKey.CtrOnly(src)
        src.fill(0)
        assertArrayEquals(ctrKey, k.ctrKey())
        k.ctrKey().fill(0)
        assertArrayEquals(ctrKey, k.ctrKey())
    }

    @Test fun `toString never leaks key material`() {
        val keys = listOf(RtbKey.CtrOnly(ctrKey), RtbKey.CtrOnly(ctrKey, deviceId), RtbKey.decode(token))
        for (k in keys) {
            val s = k.toString()
            assertFalse(s, s.contains(ctrKey.toHex()))
            assertFalse(s, s.contains(token.copyOfRange(12, 28).toHex()))
            assertFalse(s, s.contains(token.copyOfRange(28, 40).toHex()))
        }
    }
}
