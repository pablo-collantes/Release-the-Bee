package io.github.pablocollantes.releasethebee.rtb

import java.util.UUID

/**
 * RTB BLE wire protocol (docs/RTB_PROTOCOL.md).
 *
 * Requests are `[opcode, little-endian u16][payload]`, written with response to the
 * control characteristic. Replies come back as notifications on the same
 * characteristic: `[tag u16 LE][…]`, where an OK reply echoes the request opcode.
 */
object RtbProtocol {
    val SERVICE: UUID = UUID.fromString("03d5d5c4-a86c-11ee-9d89-8f2089a49e7e")
    val CONTROL: UUID = UUID.fromString("05e1f93c-d8d0-5ed8-dd88-379e4c1a3e3e")
    val AUDIO: UUID = UUID.fromString("b189a505-a86c-11ee-a5fb-8f2089a49e7e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MTU = 517
    const val AUDIO_FRAME_LEN = 495            // [seq u32 BE][491 B ciphertext]

    const val OP_SET_DEVICE_STATE = 0xC006
    const val OP_GET_DEVICE_ID = 0xC00E
    const val OP_SET_ENCRYPTION_KEY = 0xC018
    const val OP_GET_SESSION_NONCE = 0xC019
    const val OP_GET_SECURE_IDS = 0xC01B

    const val STATE_MUTE = 0
    const val STATE_RECORDING = 1

    const val TAG_OK = 0x8000
    const val TAG_ERROR = 0x8001
    const val TAG_INVALID_PARAM = 0x8002
    const val TAG_UNKNOWN_MESSAGE = 0x8003
    const val TAG_KEY_ALREADY_SET = 0x8004
    const val TAG_EVENT_DOUBLE_PRESS = 0xA001
    const val TAG_EVENT_HOLD = 0xA002
    const val TAG_EVENT_DEVICE_STATE = 0xA003
    const val TAG_EVENT_BATTERY = 0xA004
    const val TAG_EVENT_CHARGING = 0xA005

    fun request(op: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf((op and 0xFF).toByte(), ((op shr 8) and 0xFF).toByte()) + payload

    fun getSecureIds() = request(OP_GET_SECURE_IDS)
    fun getSessionNonce() = request(OP_GET_SESSION_NONCE)
    fun setEncryptionKey(token: SealToken) = request(OP_SET_ENCRYPTION_KEY, token.bytes())
    fun setDeviceState(state: Int) = request(OP_SET_DEVICE_STATE, byteArrayOf(state.toByte()))

    fun parse(d: ByteArray): Reply {
        if (d.size < 2) return Reply.Unknown(d)
        val tag = u16(d, 0)
        val rest = d.copyOfRange(2, d.size)
        return when (tag) {
            TAG_OK ->
                if (rest.size < 2) Reply.Unknown(d)
                else Reply.Ok(u16(rest, 0), rest.copyOfRange(2, rest.size))
            TAG_ERROR, TAG_INVALID_PARAM, TAG_UNKNOWN_MESSAGE ->
                Reply.Failed(tag, if (rest.size >= 2) u16(rest, 0) else null)
            TAG_KEY_ALREADY_SET -> Reply.KeyAlreadySet
            in 0xA000..0xA0FF -> Reply.Event(tag, rest)
            else -> Reply.Unknown(d)
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}

sealed interface Reply {
    class Ok(val op: Int, val payload: ByteArray) : Reply
    class Failed(val tag: Int, val op: Int?) : Reply {
        val kind: String
            get() = when (tag) {
                RtbProtocol.TAG_ERROR -> "Error"
                RtbProtocol.TAG_INVALID_PARAM -> "InvalidParam"
                else -> "UnknownMessage"
            }
    }
    data object KeyAlreadySet : Reply
    class Event(val tag: Int, val payload: ByteArray) : Reply
    class Unknown(val raw: ByteArray) : Reply
}

/** Reply to GetSecureIds: `[deviceId 8][sealed flag]`. */
class SecureIds(val deviceId: ByteArray, val sealed: Boolean) {
    companion object {
        fun parse(payload: ByteArray): SecureIds? =
            if (payload.size < 9) null else SecureIds(payload.copyOf(8), payload[8].toInt() != 0)
    }
}
