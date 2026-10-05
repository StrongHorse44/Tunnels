// FWX codec 1.2.0, canonical sha256 cd7e30e7488d2187db8d36dd1e5617d4519c4f232ee4479c2f19bd486930fccb (fieldwork codec/kotlin/src/main/kotlin/fwx/FwxKdf.kt at a4e418d)
package io.github.stronghorse44.tunnels.export.fwx

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** K_hdr and K_enc (spec section 3.3). The holder wipes them with [wipe] as soon as they are no longer needed. */
internal class FwxKeys(val hdr: ByteArray, val enc: ByteArray) {
    fun wipe() {
        hdr.fill(0)
        enc.fill(0)
    }
}

/**
 * Key derivation for FWX v1 (spec section 3): passphrase bytes, PBKDF2-HMAC-SHA256 and HKDF-SHA256, all built on
 * one HMAC-SHA256 primitive. Every intermediate buffer is zeroed after use (section 3.5); the copies that
 * SecretKeySpec makes internally cannot be reached and are a documented limit.
 */
internal object FwxKdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LENGTH = 32

    private val EXTRACT_SALT = "FWX v1 extract".toByteArray(Charsets.US_ASCII)
    private val INFO_HEADER = "FWX v1 header key".toByteArray(Charsets.US_ASCII)
    private val INFO_CHUNK = "FWX v1 chunk key".toByteArray(Charsets.US_ASCII)

    /** P, then IKM, PRK, K_hdr and K_enc. P, IKM and PRK are zeroed here. */
    fun deriveKeys(passphrase: CharArray, salt: ByteArray, iterations: Int, forWriter: Boolean): FwxKeys {
        val p = passphraseBytes(passphrase, forWriter)
        var ikm: ByteArray? = null
        var prk: ByteArray? = null
        try {
            ikm = pbkdf2(p, salt, iterations, HASH_LENGTH)
            prk = hkdfExtract(EXTRACT_SALT, ikm)
            val hdr = hkdfExpand(prk, INFO_HEADER, HASH_LENGTH)
            val enc = hkdfExpand(prk, INFO_CHUNK, HASH_LENGTH)
            return FwxKeys(hdr, enc)
        } finally {
            p.fill(0)
            ikm?.fill(0)
            prk?.fill(0)
        }
    }

    /**
     * P = UTF-8(NFC(passphrase)) (spec section 3.1). BAD_PASSPHRASE for an unpaired surrogate, an empty result or a
     * result over 1024 bytes, and for writers fewer than 12 code points after NFC. The caller zeroes the result.
     */
    fun passphraseBytes(passphrase: CharArray, forWriter: Boolean): ByteArray {
        if (passphrase.isEmpty()) bad("the passphrase is empty")
        if (hasUnpairedSurrogate(passphrase)) bad("the passphrase contains an invalid character")
        val raw = CharBuffer.wrap(passphrase)
        // Only a passphrase that is not already NFC makes the extra immutable String copy.
        val nfc: CharBuffer =
            if (Normalizer.isNormalized(raw, Normalizer.Form.NFC)) raw
            else CharBuffer.wrap(Normalizer.normalize(raw, Normalizer.Form.NFC))
        if (forWriter && Character.codePointCount(nfc, 0, nfc.length) < Fwx.MIN_WRITER_PASSPHRASE_CODE_POINTS) {
            bad("a new export needs at least ${Fwx.MIN_WRITER_PASSPHRASE_CODE_POINTS} characters")
        }
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // UTF-8 needs at most 3 bytes per UTF-16 unit (a surrogate pair is 4 bytes for 2 units).
        val out = ByteBuffer.allocate(nfc.remaining() * 3)
        try {
            val encoded = encoder.encode(nfc, out, true)
            if (encoded.isError || encoder.flush(out).isError) bad("the passphrase contains an invalid character")
            val length = out.position()
            if (length == 0) bad("the passphrase is empty")
            if (length > Fwx.MAX_PASSPHRASE_BYTES) bad("the passphrase is longer than ${Fwx.MAX_PASSPHRASE_BYTES} bytes")
            val p = ByteArray(length)
            System.arraycopy(out.array(), 0, p, 0, length)
            return p
        } finally {
            out.array().fill(0)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018 section 5.2) for any dkLen, over one reused Mac. The caller zeroes the result.
     * The format only uses dkLen = 32; the general loop is what the RFC 7914 self-tests check.
     */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations >= 1 && dkLen >= 1)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(password, HMAC))
        val blocks = (dkLen + HASH_LENGTH - 1) / HASH_LENGTH
        val out = ByteArray(dkLen)
        val u = ByteArray(HASH_LENGTH)
        val t = ByteArray(HASH_LENGTH)
        val index = ByteArray(4)
        try {
            for (block in 1..blocks) {
                Fwx.putU32(index, 0, block.toLong())
                mac.update(salt)
                mac.update(index)
                mac.doFinal(u, 0)
                System.arraycopy(u, 0, t, 0, HASH_LENGTH)
                for (j in 2..iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (k in 0 until HASH_LENGTH) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val at = (block - 1) * HASH_LENGTH
                System.arraycopy(t, 0, out, at, minOf(HASH_LENGTH, dkLen - at))
            }
            return out
        } finally {
            u.fill(0)
            t.fill(0)
        }
    }

    /** HKDF-Extract (RFC 5869 section 2.2). An empty salt means HashLen zero bytes, as the RFC says. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)

    /** HKDF-Expand (RFC 5869 section 2.3): T(i) = HMAC(PRK, T(i-1) | info | i). The caller zeroes the result. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LENGTH)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val out = ByteArray(length)
        val t = ByteArray(HASH_LENGTH)
        var tLength = 0
        var at = 0
        var i = 1
        try {
            while (at < length) {
                mac.update(t, 0, tLength)
                mac.update(info)
                mac.update(i.toByte())
                mac.doFinal(t, 0)
                tLength = HASH_LENGTH
                val n = minOf(HASH_LENGTH, length - at)
                System.arraycopy(t, 0, out, at, n)
                at += n
                i++
            }
            return out
        } finally {
            t.fill(0)
        }
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(message)
    }

    /** Compares every byte pair with no early exit (spec section 3.4). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun hasUnpairedSurrogate(c: CharArray): Boolean {
        var i = 0
        while (i < c.size) {
            val ch = c[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= c.size || !Character.isLowSurrogate(c[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(ch)) return true
                i++
            }
        }
        return false
    }

    private fun bad(reason: String): Nothing = throw FwxException(FwxError.BAD_PASSPHRASE, reason)
}
