package io.github.pablocollantes.releasethebee.rtb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtbProtocolTest {

    private fun bytes(vararg i: Int) = ByteArray(i.size) { i[it].toByte() }

    @Test fun `requests are little-endian opcodes`() {
        assertArrayEquals(bytes(0x1B, 0xC0), RtbProtocol.getSecureIds())
        assertArrayEquals(bytes(0x19, 0xC0), RtbProtocol.getSessionNonce())
        assertArrayEquals(bytes(0x06, 0xC0, 0x01), RtbProtocol.setDeviceState(RtbProtocol.STATE_RECORDING))
        assertArrayEquals(bytes(0x06, 0xC0, 0x00), RtbProtocol.setDeviceState(RtbProtocol.STATE_MUTE))
    }

    @Test fun `seal request is opcode plus the whole token`() {
        val raw = "SKV1".toByteArray() + ByteArray(104) { it.toByte() }
        val req = RtbProtocol.setEncryptionKey(SealToken.fromBytes(raw))
        assertEquals(110, req.size)
        assertArrayEquals(bytes(0x18, 0xC0), req.copyOf(2))
        assertArrayEquals(raw, req.copyOfRange(2, 110))
    }

    @Test fun `parses an OK SecureIds reply`() {
        val id = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        val r = RtbProtocol.parse(bytes(0x00, 0x80, 0x1B, 0xC0) + id + bytes(0x01)) as Reply.Ok
        assertEquals(RtbProtocol.OP_GET_SECURE_IDS, r.op)
        val ids = SecureIds.parse(r.payload)!!
        assertArrayEquals(id, ids.deviceId)
        assertTrue(ids.sealed)
        assertEquals(false, SecureIds.parse(id + bytes(0))!!.sealed)
        assertNull(SecureIds.parse(id))
    }

    @Test fun `parses a session nonce`() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val r = RtbProtocol.parse(bytes(0x00, 0x80, 0x19, 0xC0) + nonce + bytes(0xEE)) as Reply.Ok
        assertEquals(RtbProtocol.OP_GET_SESSION_NONCE, r.op)
        assertArrayEquals(nonce, r.payload.copyOf(12))
    }

    @Test fun `parses failures with the echoed opcode`() {
        // Seen on hardware when sealing with a bare 16-byte key: 02 80 18 c0
        val r = RtbProtocol.parse(bytes(0x02, 0x80, 0x18, 0xC0)) as Reply.Failed
        assertEquals("InvalidParam", r.kind)
        assertEquals(RtbProtocol.OP_SET_ENCRYPTION_KEY, r.op)
        assertNull((RtbProtocol.parse(bytes(0x01, 0x80)) as Reply.Failed).op)
    }

    @Test fun `parses already-set, events and junk`() {
        assertEquals(Reply.KeyAlreadySet, RtbProtocol.parse(bytes(0x04, 0x80)))
        val e = RtbProtocol.parse(bytes(0x04, 0xA0, 0x55)) as Reply.Event
        assertEquals(RtbProtocol.TAG_EVENT_BATTERY, e.tag)
        assertArrayEquals(bytes(0x55), e.payload)
        assertTrue(RtbProtocol.parse(bytes(0x01)) is Reply.Unknown)
        assertTrue(RtbProtocol.parse(bytes(0x00, 0x80, 0x1B)) is Reply.Unknown)
    }

    @Test fun `battery request and its reply`() {
        assertArrayEquals(byteArrayOf(0x0F, 0xC0.toByte()), RtbProtocol.getBattery())
        val ok = RtbProtocol.parse(byteArrayOf(0x00, 0x80.toByte(), 0x0F, 0xC0.toByte(), 0x32, 0x01)) as Reply.Ok
        assertEquals(RtbProtocol.OP_GET_BATTERY, ok.op)
        val b = BatteryState.parse(ok.payload)!!
        assertEquals(50, b.level)
        assertEquals(true, b.charging)
        assertEquals(null, BatteryState.parse(byteArrayOf(0x32))!!.charging)
        assertNull(BatteryState.parse(byteArrayOf(0xC8.toByte())))       // 200: not a level
        assertNull(BatteryState.parse(ByteArray(0)))
    }
}
