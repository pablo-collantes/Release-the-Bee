package io.github.pablocollantes.releasethebee.rtb

import java.util.Base64

/**
 * The 108-byte seal token an RTB must be given before it will stream audio:
 *
 *     "SKV1" (4) | deviceId (8) | ctrKey (16) | signature (80)
 *
 * It is fixed per device and signed by the vendor, so it can't be generated, only
 * imported from a capture of the stock pairing (tools/parse_bee_token.py). It is also
 * a secret: whoever holds it can seal that RTB and decrypt its audio. toString() never
 * prints key material, and nothing in the app logs it.
 */
class SealToken private constructor(private val raw: ByteArray) {

    val deviceId: ByteArray get() = raw.copyOfRange(4, 12)
    val deviceIdHex: String get() = deviceId.toHex()

    /** AES-128-CTR key for this RTB's audio. */
    fun ctrKey(): ByteArray = raw.copyOfRange(12, 28)

    fun bytes(): ByteArray = raw.copyOf()

    override fun toString() = "SealToken(device=$deviceIdHex)"

    companion object {
        const val LENGTH = 108
        private val MAGIC = byteArrayOf(0x53, 0x4B, 0x56, 0x31) // "SKV1"
        private val SEPARATORS = Regex("0x|[\\s:,_-]", RegexOption.IGNORE_CASE)
        private val HEX = Regex("[0-9a-fA-F]+")

        /** The raw token, optionally still carrying the `18 c0` SetEncryptionKey opcode. */
        fun fromBytes(b: ByteArray): SealToken {
            val t = if (b.size == LENGTH + 2 && b[0] == 0x18.toByte() && b[1] == 0xC0.toByte())
                b.copyOfRange(2, b.size) else b
            require(t.size == LENGTH) { "a seal token is $LENGTH bytes; this is ${t.size}" }
            require(t.copyOf(4).contentEquals(MAGIC)) { "not a seal token (it doesn't start with SKV1)" }
            return SealToken(t.copyOf())
        }

        /** Pasted text: hex in any common spacing (optionally the `18c0…` wire form), or base64. */
        fun parse(text: String): SealToken {
            val s = text.trim()
            val hex = s.replace(SEPARATORS, "")
            if (hex.isNotEmpty() && hex.matches(HEX)) return fromBytes(hex.hexToBytes())
            val b64 = runCatching { Base64.getDecoder().decode(s.replace(Regex("\\s"), "")) }.getOrNull()
            if (b64 != null && b64.isNotEmpty()) return fromBytes(b64)
            throw IllegalArgumentException("expected ${LENGTH * 2} hex characters (or base64)")
        }

        /** An imported file: the raw bytes, or text holding hex/base64. */
        fun fromFile(content: ByteArray): SealToken =
            runCatching { fromBytes(content) }.getOrNull()
                ?: parse(String(content, Charsets.UTF_8))
    }
}
