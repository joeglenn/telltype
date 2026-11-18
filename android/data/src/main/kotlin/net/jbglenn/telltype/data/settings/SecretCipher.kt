package net.jbglenn.telltype.data.settings

import net.jbglenn.telltype.core.common.Outcome

/**
 * Encrypts the server key before it is stored (docs/PLAN.md section 11). An interface so JVM tests use a fake;
 * the app uses [KeystoreSecretCipher]. Implementations never throw.
 */
internal interface SecretCipher {
    /** Seals [plaintext]; the result carries everything [open] needs except the key. */
    fun seal(plaintext: ByteArray): Outcome<ByteArray, CipherFailure>

    /** Opens what [seal] produced. */
    fun open(sealed: ByteArray): Outcome<ByteArray, CipherFailure>
}

/** Why a [SecretCipher] call failed. */
internal enum class CipherFailure {
    /** The key is gone (key store wiped, app data restored to another device): stored secrets are lost. */
    KEY_UNAVAILABLE,

    /** The sealed bytes are not ours or were changed (wrong format, authentication tag mismatch). */
    CORRUPT,

    /** The platform's crypto failed for another reason. */
    FAILED,
}
