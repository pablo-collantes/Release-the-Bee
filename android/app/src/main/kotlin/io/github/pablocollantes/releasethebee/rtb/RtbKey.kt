package io.github.pablocollantes.releasethebee.rtb

import java.util.Base64

/**
 * What the app holds to talk to an RTB. Either:
 *
 *  - [Full]: the 108-byte seal token. It can seal the RTB (after a reset) and its
 *    deviceId says which RTB it belongs to.
 *  - [CtrOnly]: just the 16-byte AES ctrKey. It decrypts audio but can't seal, and it
 *    doesn't say which RTB it belongs to: the link binds it to the first RTB it reaches
 *    and remembers that deviceId from then on.
 *
 * Both are secrets. toString() never prints key material.
 */
sealed class RtbKey {

    /** AES-128-CTR key for the RTB's audio. A fresh copy. */
    abstract fun ctrKey(): ByteArray

    /** The RTB this key is for, or null for a ctrKey that hasn't reached an RTB yet. */
    abstract val deviceId: ByteArray?
    val deviceIdHex: String? get() = deviceId?.toHex()

    /** Whether this key can seal an RTB that has been reset. */
    val canSeal: Boolean get() = this is Full

    /** Stored form for [decode]. A full token is stored as its 108 raw bytes, as before ctrKey support. */
    abstract fun encode(): ByteArray

    /** Same key material, whatever the binding. */
    fun sameKeyAs(other: RtbKey): Boolean = when {
        this is Full && other is Full -> token.bytes().contentEquals(other.token.bytes())
        this is CtrOnly && other is CtrOnly -> ctrKey().contentEquals(other.ctrKey())
        else -> false
    }

    class Full(val token: SealToken) : RtbKey() {
        override fun ctrKey() = token.ctrKey()
        override val deviceId: ByteArray get() = token.deviceId
        override fun encode() = token.bytes()
        override fun toString() = "RtbKey.Full(device=${token.deviceIdHex})"
    }

    class CtrOnly(key: ByteArray, deviceId: ByteArray? = null) : RtbKey() {
        private val key = key.copyOf()
        private val bound = deviceId?.copyOf()

        init {
            require(key.size == CTR_KEY_LENGTH) { "a ctrKey is $CTR_KEY_LENGTH bytes; this is ${key.size}" }
            require(deviceId == null || deviceId.size == DEVICE_ID_LENGTH) { "a deviceId is $DEVICE_ID_LENGTH bytes" }
        }

        override fun ctrKey() = key.copyOf()
        override val deviceId: ByteArray? get() = bound?.copyOf()

        /** The same key, bound to the RTB with [id]. */
        fun boundTo(id: ByteArray) = CtrOnly(key, id)

        /** The same key, free to bind to the next RTB the link reaches. */
        fun unbound() = CtrOnly(key, null)

        override fun encode() = CTR_MAGIC + key + (bound ?: ByteArray(0))
        override fun toString() = "RtbKey.CtrOnly(device=${deviceIdHex ?: "unbound"})"
    }

    companion object {
        const val CTR_KEY_LENGTH = 16
        const val DEVICE_ID_LENGTH = 8

        /** Prefix of a stored ctrKey-only key: "RTBK" | ctrKey (16) [| deviceId (8)]. */
        private val CTR_MAGIC = byteArrayOf(0x52, 0x54, 0x42, 0x4B)
        private val SEPARATORS = Regex("0x|[\\s:,_-]", RegexOption.IGNORE_CASE)
        private val HEX = Regex("[0-9a-fA-F]+")
        private val WHITESPACE = Regex("\\s")
        private const val EXPECTED =
            "expected the 108-byte seal token (216 hex characters) or the 16-byte ctrKey (32 hex characters), as hex or base64"

        /** Read back what [encode] stored. A raw 108-byte seal token (the 0.1 format) is a [Full] key. */
        fun decode(stored: ByteArray): RtbKey {
            if (stored.size >= CTR_MAGIC.size && stored.copyOf(CTR_MAGIC.size).contentEquals(CTR_MAGIC)) {
                val body = stored.copyOfRange(CTR_MAGIC.size, stored.size)
                return when (body.size) {
                    CTR_KEY_LENGTH -> CtrOnly(body)
                    CTR_KEY_LENGTH + DEVICE_ID_LENGTH ->
                        CtrOnly(body.copyOf(CTR_KEY_LENGTH), body.copyOfRange(CTR_KEY_LENGTH, body.size))
                    else -> throw IllegalArgumentException("stored ctrKey has the wrong length")
                }
            }
            return Full(SealToken.fromBytes(stored))
        }

        /** Raw key bytes: 16 → ctrKey, 108 (or the 110-byte `18 c0` wire form) → seal token. */
        fun fromBytes(b: ByteArray): RtbKey = when {
            b.size == CTR_KEY_LENGTH -> CtrOnly(b)
            looksLikeToken(b) -> Full(SealToken.fromBytes(b))
            else -> throw IllegalArgumentException("$EXPECTED; this is ${b.size} bytes")
        }

        /** Pasted text: hex in any common spacing, or base64, of either a seal token or a ctrKey. */
        fun parse(text: String): RtbKey {
            val s = text.trim()
            require(s.isNotEmpty()) { EXPECTED }
            val hex = s.replace(SEPARATORS, "")
            val hexLike = hex.isNotEmpty() && hex.matches(HEX)
            val fromHex = if (hexLike && hex.length % 2 == 0) hex.hexToBytes() else null
            val fromB64 = runCatching { Base64.getDecoder().decode(s.replace(WHITESPACE, "")) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
            listOfNotNull(fromHex, fromB64).firstOrNull { isKeyLength(it) }?.let { return fromBytes(it) }
            if (hexLike) {
                throw IllegalArgumentException(
                    "that's ${hex.length} hex characters; a ctrKey is ${CTR_KEY_LENGTH * 2} and a seal token ${SealToken.LENGTH * 2}",
                )
            }
            if (fromB64 != null) return fromBytes(fromB64)    // throws, naming the length
            throw IllegalArgumentException(EXPECTED)
        }

        /**
         * An imported file: raw bytes (a 16-byte ctrKey or a 108-byte token), or text holding
         * hex/base64 of either. A 16-byte file made only of hex digits is text (half a key),
         * not a key: a random key looks like that with odds of about 1 in 10^17.
         */
        fun fromFile(content: ByteArray): RtbKey {
            if (content.size == CTR_KEY_LENGTH && !isHexText(content)) return CtrOnly(content)
            // No text encoding of a key is 108 or 110 bytes long, so these are raw token bytes.
            if (looksLikeToken(content)) return Full(SealToken.fromBytes(content))
            return parse(String(content, Charsets.UTF_8))
        }

        private fun isKeyLength(b: ByteArray) = b.size == CTR_KEY_LENGTH || looksLikeToken(b)

        private fun looksLikeToken(b: ByteArray) = b.size == SealToken.LENGTH ||
            (b.size == SealToken.LENGTH + 2 && b[0] == 0x18.toByte() && b[1] == 0xC0.toByte())

        private fun isHexText(b: ByteArray) = b.all { val c = it.toInt().toChar(); c.isWhitespace() || c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }
}
