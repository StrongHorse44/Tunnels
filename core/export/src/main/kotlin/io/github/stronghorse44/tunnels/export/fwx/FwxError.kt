// FWX codec 1.2.0, canonical sha256 1bb7d381136f8f243d5909651d3a31c5a736731833e19d8047bc04b1d257ed72 (fieldwork codec/kotlin/src/main/kotlin/fwx/FwxError.kt at a4e418d)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.IOException

/** The shared error codes of FWX v1 (spec section 8). Apps map each code to the user message in that table. */
enum class FwxError {
    NOT_AN_EXPORT,
    LEGACY,
    UNSUPPORTED_VERSION,
    MALFORMED_HEADER,
    UNSUPPORTED_KDF,
    KDF_PARAMS,
    WRONG_APP,
    SCHEMA_TOO_NEW,
    SCHEMA_TOO_OLD,
    BAD_PASSPHRASE,
    WRONG_PASSPHRASE,
    DAMAGED,
    MALFORMED_PAYLOAD,
    TOO_LARGE,
}

/**
 * The one exception the codec throws for a file or input problem. [detail] is a short English note for diagnostics and
 * tests; it never holds key material, the passphrase, entry names or entry contents. [otherAppId] is set for
 * WRONG_APP so the app can name the other app from the registry.
 *
 * It extends IOException so it passes unchanged through stream code such as an entry stream's read().
 */
class FwxException(
    val code: FwxError,
    val detail: String,
    val otherAppId: String? = null,
) : IOException("$code: $detail")
