package net.jbglenn.telltype.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.jbglenn.telltype.core.common.Outcome

/**
 * [SecretCipher] with a 256-bit AES-GCM key that lives in the Android Keystore and never leaves it (no deprecated
 * security-crypto library). Sealed format: one version byte, the 12-byte IV, then ciphertext with the 128-bit tag.
 * The key is created on the first [seal]; when the key store was wiped, [open] reports
 * [CipherFailure.KEY_UNAVAILABLE] (or [CipherFailure.CORRUPT] once a new key exists), which settings treat as "no
 * key set".
 *
 * @param alias the Keystore alias of the key.
 */
internal class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {
    override fun seal(plaintext: ByteArray): Outcome<ByteArray, CipherFailure> = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        Outcome.Ok(byteArrayOf(FORMAT_VERSION) + iv + cipher.doFinal(plaintext))
    }

    override fun open(sealed: ByteArray): Outcome<ByteArray, CipherFailure> = guarded {
        if (sealed.size < HEADER_BYTES + TAG_BYTES || sealed[0] != FORMAT_VERSION) {
            return@guarded Outcome.Err(CipherFailure.CORRUPT)
        }
        val key = existingKey() ?: return@guarded Outcome.Err(CipherFailure.KEY_UNAVAILABLE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, sealed, 1, IV_BYTES))
        Outcome.Ok(cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private inline fun guarded(block: () -> Outcome<ByteArray, CipherFailure>): Outcome<ByteArray, CipherFailure> =
        try {
            block()
        } catch (_: AEADBadTagException) {
            Outcome.Err(CipherFailure.CORRUPT)
        } catch (_: GeneralSecurityException) {
            Outcome.Err(CipherFailure.FAILED)
        } catch (_: ProviderException) {
            Outcome.Err(CipherFailure.FAILED)
        } catch (_: IllegalStateException) {
            Outcome.Err(CipherFailure.FAILED)
        } catch (_: IOException) {
            Outcome.Err(CipherFailure.FAILED)
        }

    private companion object {
        const val DEFAULT_ALIAS = "telltype_server_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + IV_BYTES
        const val KEY_BITS = 256
    }
}
