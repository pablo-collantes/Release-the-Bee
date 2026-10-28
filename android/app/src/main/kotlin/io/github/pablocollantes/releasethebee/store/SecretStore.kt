package io.github.pablocollantes.releasethebee.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.pablocollantes.releasethebee.rtb.RtbKey
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest: AES-256-GCM under a key that lives in the Android Keystore and never
 * leaves it. The ciphertext sits in app-private prefs, and the app opts out of backups,
 * so a restored or copied data directory can't be decrypted (the user re-imports instead).
 */
class SecretStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /**
     * The RTB key: a full seal token or a bare ctrKey (with the RTB it bound to).
     * Stored under the 0.1 name: a full token is still its 108 raw bytes, so a token
     * saved before ctrKey support reads back as [RtbKey.Full] with no migration step.
     */
    var rtbKey: RtbKey?
        get() = getBytes(RTB_KEY)?.let { runCatching { RtbKey.decode(it) }.getOrNull() }
        set(v) = putBytes(RTB_KEY, v?.encode())

    /** Optional bearer token for the WebSocket receiver or relay. */
    var sinkToken: String?
        get() = getBytes(SINK_TOKEN)?.toString(Charsets.UTF_8)
        set(v) = putBytes(SINK_TOKEN, v?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray())

    private fun putBytes(name: String, value: ByteArray?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(value)
        prefs.edit().putString(name, Base64.getEncoder().encodeToString(blob)).apply()
    }

    private fun getBytes(name: String): ByteArray? {
        val blob = prefs.getString(name, null)?.let { Base64.getDecoder().decode(it) } ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
            c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        } catch (e: GeneralSecurityException) {
            null   // Keystore key is gone (e.g. data moved to another phone)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "rtb-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val RTB_KEY = "seal_token"     // the 0.1 name, kept so stored tokens keep working
        const val SINK_TOKEN = "sink_token"
    }
}
