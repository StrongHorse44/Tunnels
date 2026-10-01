package io.github.stronghorse44.tunnels.certs

import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * What the trust store tunnel keeps about one certificate: identifiers and facts, never the
 * certificate itself. Pure JVM, so it is unit-tested with certificates embedded in the tests.
 */
data class CertSummary(
    /** SHA-256 over the DER encoding, uppercase hex with colons. */
    val fingerprint: String,
    val subjectCn: String?,
    val subjectO: String?,
    val issuerCn: String?,
    /** ISO dates (UTC), e.g. `2036-09-28`. */
    val notBefore: String,
    val notAfter: String,
    /** `RSA`, `EC`, `DSA`, `Ed25519`, ... */
    val keyAlgorithm: String,
    /** Modulus, field or group size in bits; 0 when unknown. */
    val keySize: Int,
    /** Basic constraints say this certificate may sign others. */
    val isCa: Boolean,
    /** Subject equals issuer and the signature verifies with its own key. */
    val selfSigned: Boolean,
    val signatureAlgorithm: String,
) {
    /** First four and last four fingerprint bytes: enough to tell certificates apart on a phone screen. */
    val shortFingerprint: String get() = shortForm(fingerprint)

    /** Common name, else organization, else the short fingerprint. */
    val displayName: String get() = subjectCn?.takeIf { it.isNotBlank() } ?: subjectO?.takeIf { it.isNotBlank() } ?: shortFingerprint

    /** `RSA 2048`, `EC 256`; just the algorithm when the size is unknown. */
    val keyDescription: String get() = if (keySize > 0) "$keyAlgorithm $keySize" else keyAlgorithm

    companion object {
        fun of(cert: X509Certificate): CertSummary {
            val subject = DistinguishedName.of(cert.subjectX500Principal)
            val issuer = DistinguishedName.of(cert.issuerX500Principal)
            return CertSummary(
                fingerprint = fingerprint(cert.encoded),
                subjectCn = subject.commonName,
                subjectO = subject.organization,
                issuerCn = issuer.commonName,
                notBefore = isoDate(cert.notBefore),
                notAfter = isoDate(cert.notAfter),
                keyAlgorithm = keyAlgorithm(cert.publicKey),
                keySize = keySize(cert.publicKey),
                isCa = cert.basicConstraints != -1,
                selfSigned = isSelfSigned(cert),
                signatureAlgorithm = cert.sigAlgName,
            )
        }

        /** `AB:CD:...` uppercase SHA-256 of [der]. */
        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

        /** `E271773B…4275C265` for a colon-separated fingerprint; the input unchanged when it is not one. */
        fun shortForm(fingerprint: String): String {
            val hex = fingerprint.replace(":", "")
            if (hex.length <= 16) return hex
            return hex.take(8) + "…" + hex.takeLast(8)
        }

        private fun isoDate(date: Date): String = Instant.ofEpochMilli(date.time).atOffset(ZoneOffset.UTC).toLocalDate().toString()

        private fun keyAlgorithm(key: PublicKey): String = when (val name = key.algorithm) {
            "EdDSA" -> "Ed25519"
            "1.2.840.10045.2.1" -> "EC"
            else -> name
        }

        private fun keySize(key: PublicKey): Int = when (key) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.curve.field.fieldSize
            is DSAPublicKey -> key.params.p.bitLength()
            else -> when {
                key.algorithm.contains("25519") || key.algorithm == "EdDSA" -> 256
                key.algorithm.contains("448") -> 448
                else -> 0
            }
        }

        private fun isSelfSigned(cert: X509Certificate): Boolean {
            if (cert.subjectX500Principal != cert.issuerX500Principal) return false
            return try {
                cert.verify(cert.publicKey)
                true
            } catch (e: java.security.SignatureException) {
                false
            } catch (e: java.security.InvalidKeyException) {
                false
            } catch (e: Exception) {
                // Unsupported algorithm or provider trouble: fall back to the name comparison above.
                true
            }
        }
    }
}
