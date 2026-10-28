package io.github.pablocollantes.releasethebee.rtb

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts RTB audio notifications for one session.
 *
 * Each notification is `[seq u32 BE][ciphertext]`. The cipher is AES-128-CTR with
 * IV = sessionNonce (12) || seq (4, BE), restarted for every frame. The plaintext is
 * ADTS AAC-LC, 16 kHz mono. The nonce changes every session, so build a new
 * decryptor after every GetSessionNonce.
 */
class AudioDecryptor(ctrKey: ByteArray, nonce: ByteArray) {

    init {
        require(ctrKey.size == 16) { "ctrKey must be 16 bytes" }
        require(nonce.size == 12) { "session nonce must be 12 bytes" }
    }

    private val key = SecretKeySpec(ctrKey.copyOf(), "AES")
    private val iv = nonce.copyOf(16)
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    fun decrypt(frame: ByteArray): Frame? {
        if (frame.size < 5) return null
        frame.copyInto(iv, destinationOffset = 12, startIndex = 0, endIndex = 4)
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val seq = ((frame[0].toLong() and 0xFF) shl 24) or ((frame[1].toLong() and 0xFF) shl 16) or
            ((frame[2].toLong() and 0xFF) shl 8) or (frame[3].toLong() and 0xFF)
        return Frame(seq, cipher.doFinal(frame, 4, frame.size - 4))
    }

    class Frame(val seq: Long, val adts: ByteArray) {
        /**
         * Whether the plaintext contains an ADTS header (sync 0xFFF, layer 0). A cheap check
         * that key and nonce are right. A notification doesn't have to *start* with one,
         * because ADTS frames run across notification boundaries. But RTB frames are about
         * 64 bytes, so a 491-byte notification always holds several. Random bytes (a stale
         * nonce) rarely do.
         */
        val containsAdtsSync: Boolean
            get() {
                for (i in 0 until adts.size - 1) {
                    if (adts[i] == 0xFF.toByte() && (adts[i + 1].toInt() and 0xF6) == 0xF0) return true
                }
                return false
            }
    }
}
