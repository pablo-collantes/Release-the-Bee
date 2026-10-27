package io.github.pablocollantes.releasethebee.rtb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class AudioDecryptorTest {

    private val key = Random(1).nextBytes(16)
    private val nonce = Random(2).nextBytes(12)

    /** Independent CTR: keystream block i = AES-ECB(nonce || seq, +i on the full 128-bit counter). */
    private fun encrypt(seq: Long, pt: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val counter = java.math.BigInteger(1, nonce + byteArrayOf((seq shr 24).toByte(), (seq shr 16).toByte(), (seq shr 8).toByte(), seq.toByte()))
        val out = ByteArray(pt.size)
        for (block in 0 until (pt.size + 15) / 16) {
            val ctr = counter.add(java.math.BigInteger.valueOf(block.toLong())).toByteArray().takeLast(16).toByteArray()
            val ks = ecb.doFinal(ByteArray(16 - ctr.size) + ctr)
            for (i in 0 until 16) {
                val p = block * 16 + i
                if (p < pt.size) out[p] = (pt[p].toInt() xor ks[i].toInt()).toByte()
            }
        }
        return out
    }

    private fun frame(seq: Long, ct: ByteArray) =
        byteArrayOf((seq shr 24).toByte(), (seq shr 16).toByte(), (seq shr 8).toByte(), seq.toByte()) + ct

    @Test fun `decrypts with IV = nonce plus big-endian seq`() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + Random(3).nextBytes(489)  // 491 B like the RTB
        val dec = AudioDecryptor(key, nonce)
        for (seq in listOf(0L, 1L, 258L, 0xFFFFFFFFL)) {
            val f = dec.decrypt(frame(seq, encrypt(seq, adts)))!!
            assertEquals(seq, f.seq)
            assertArrayEquals(adts, f.adts)
            assertTrue(f.looksLikeAdts)
        }
    }

    @Test fun `wrong nonce gives non-AAC`() {
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + ByteArray(100)
        val f = AudioDecryptor(key, ByteArray(12)).decrypt(frame(5, encrypt(5, adts)))!!
        assertFalse(f.adts.contentEquals(adts))
    }

    @Test fun `ignores runt frames`() {
        assertNull(AudioDecryptor(key, nonce).decrypt(ByteArray(4)))
    }
}
