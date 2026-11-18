package net.jbglenn.telltype.ui.screens.common

import net.jbglenn.telltype.core.model.settings.CertificateFingerprint

/**
 * The fingerprint as people compare it: upper-case byte pairs separated by colons, [bytesPerLine] pairs per line
 * (the whole fingerprint), or, when [short], only the first six pairs followed by an ellipsis.
 */
internal fun formatFingerprint(
    fingerprint: CertificateFingerprint,
    short: Boolean = false,
    bytesPerLine: Int = 8,
): String {
    val pairs = fingerprint.sha256Hex.uppercase().chunked(2)
    return if (short) {
        pairs.take(FingerprintFormat.SHORT_BYTES).joinToString(":") + "…"
    } else {
        pairs.chunked(bytesPerLine).joinToString("\n") { it.joinToString(":") }
    }
}

/** Fixed values used in this file. */
private object FingerprintFormat {
    const val SHORT_BYTES = 6
}
