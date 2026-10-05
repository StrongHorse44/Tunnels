// FWX codec 1.2.0, canonical sha256 36fe11e3c8b67c3abe3271cc51ca2902db797e3ef006b5131e72d906cab5dad5 (fieldwork codec/kotlin/src/main/kotlin/fwx/FwxReader.kt at a4e418d)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.Closeable
import java.io.InputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One entry from [FwxReader.next]. [stream] yields exactly [length] bytes, decrypting chunk by chunk. */
class FwxEntry internal constructor(val name: String, val length: Long, val stream: InputStream)

/**
 * Opens an FWX v1 bundle (spec section 5.1). The constructor runs steps 1 to 7: header checks, then [expectedAppId]
 * (WRONG_APP) and [schemaRange] (SCHEMA_TOO_OLD / SCHEMA_TOO_NEW), then key derivation and the header MAC
 * (WRONG_PASSPHRASE). No chunk byte is read before the MAC verifies.
 *
 * Then call [next] until it returns null. Each entry's stream must be read to its end (or skipped, which still
 * decrypts and verifies) before the next call. null comes back only after the end record, entry_count, the final
 * chunk flag and end of file have all verified; until then nothing read is known to be complete, so importers stage
 * entries and commit only after null (spec section 5.2). Every failure is an [FwxException] from the constructor,
 * [next] or an entry stream's read; any later call on a failed reader is an IllegalStateException.
 *
 * [expectedAppId] and [schemaRange] are null only in tools and tests that open any app's bundle. [maxStreamBytes]
 * caps the plaintext stream (TOO_LARGE). The caller owns [input] and the passphrase; [close] wipes buffers only.
 */
class FwxReader(
    private val input: InputStream,
    passphrase: CharArray,
    expectedAppId: String?,
    schemaRange: LongRange?,
    private val maxStreamBytes: Long = Fwx.MAX_STREAM_BYTES,
) : Closeable {
    val header: FwxHeader

    private val key: SecretKeySpec
    private val aad: ByteArray
    private val cipher: Cipher
    private val chunkSize: Int

    private var current: ByteArray
    private var lookahead: ByteArray
    private var currentLength = 0
    private val plain: ByteArray
    private var plainLength = 0
    private var plainAt = 0
    private var counter = 0L
    private var started = false
    private var sawFinal = false

    private var consumed = 0L
    private var entries = 0
    private val names = FwxNameSet()
    private var open: EntryStream? = null
    private var done = false
    private var failed = false
    private var closed = false

    init {
        require(maxStreamBytes in 5..Fwx.MAX_STREAM_BYTES)
        val parsed = Fwx.parseHeader(input, null)
        if (expectedAppId != null && parsed.appId != expectedAppId) {
            throw FwxException(FwxError.WRONG_APP, "bundle is for another app", otherAppId = parsed.appId)
        }
        if (schemaRange != null) {
            if (parsed.schemaVersion > schemaRange.last) throw FwxException(FwxError.SCHEMA_TOO_NEW, "schema too new")
            if (parsed.schemaVersion < schemaRange.first) throw FwxException(FwxError.SCHEMA_TOO_OLD, "schema too old")
        }
        val keys = FwxKdf.deriveKeys(passphrase, parsed.salt, parsed.kdfIterations, forWriter = false)
        try {
            val mac = FwxKdf.hmac(keys.hdr, parsed.bytes)
            if (!FwxKdf.constantTimeEquals(mac, parsed.mac)) {
                throw FwxException(FwxError.WRONG_PASSPHRASE, "header MAC does not match")
            }
            key = SecretKeySpec(keys.enc, "AES")
        } finally {
            keys.wipe()
        }
        header = parsed.asVerified()
        aad = parsed.bytes + parsed.mac
        chunkSize = parsed.chunkSize
        cipher = Cipher.getInstance("AES/GCM/NoPadding")
        current = ByteArray(chunkSize + Fwx.TAG_LENGTH)
        lookahead = ByteArray(chunkSize + Fwx.TAG_LENGTH)
        plain = ByteArray(chunkSize)
    }

    /** The next entry, or null after a fully verified end (spec section 4.4). */
    fun next(): FwxEntry? {
        check(!failed) { "the reader failed earlier" }
        check(!closed) { "the reader is closed" }
        check((open?.remaining ?: 0L) == 0L) { "read or skip the previous entry to its end first" }
        if (done) return null
        return guarded {
            when (val type = takeByte()) {
                0x00 -> {
                    val count = Fwx.u32(take(4), 0)
                    if (count != entries.toLong()) payload("entry_count does not match")
                    if (morePlaintext()) payload("bytes after the end record")
                    done = true
                    wipe()
                    null
                }
                0x01 -> {
                    if (entries >= Fwx.MAX_ENTRIES) payload("more than ${Fwx.MAX_ENTRIES} entries")
                    val nameLength = Fwx.u16(take(2), 0)
                    if (nameLength == 0 || nameLength > Fwx.MAX_NAME_LENGTH) payload("name_len $nameLength")
                    val name = Fwx.entryNameOrNull(take(nameLength)) ?: payload("invalid entry name")
                    names.add(name)?.let { payload(it) }
                    val dataLength = Fwx.u64(take(8), 0)
                    if (dataLength < 0 || dataLength > Fwx.MAX_ENTRY_BYTES) tooLarge("data_len above the format limit")
                    if (consumed + dataLength > maxStreamBytes) tooLarge("bundle is larger than this reader accepts")
                    entries++
                    val stream = EntryStream(dataLength)
                    open = stream
                    FwxEntry(name, dataLength, stream)
                }
                else -> payload("record_type $type")
            }
        }
    }

    override fun close() {
        closed = true
        wipe()
    }

    private fun wipe() {
        plain.fill(0)
        current.fill(0)
        lookahead.fill(0)
        plainLength = 0
        plainAt = 0
    }

    /** Takes exactly [n] bytes of P for a record head; the stream ending first is MALFORMED_PAYLOAD. */
    private fun take(n: Int): ByteArray {
        if (consumed + n > maxStreamBytes) tooLarge("bundle is larger than this reader accepts")
        val b = ByteArray(n)
        var at = 0
        while (at < n) {
            if (plainAt == plainLength && !refill()) payload("stream ends inside a record or before the end record")
            val k = minOf(n - at, plainLength - plainAt)
            System.arraycopy(plain, plainAt, b, at, k)
            plainAt += k
            at += k
        }
        consumed += n
        return b
    }

    private fun takeByte(): Int = take(1)[0].toInt() and 0xFF

    private fun morePlaintext(): Boolean = plainAt < plainLength || refill()

    /**
     * Decrypts the next chunk into [plain] (spec section 2.4). Returns false once the final chunk has been used up.
     * Finality comes from looking one block ahead; the reader never tries a second flag value.
     */
    private fun refill(): Boolean {
        if (sawFinal) return false
        if (!started) {
            currentLength = Fwx.readUpTo(input, current, 0, current.size)
            started = true
        }
        if (currentLength < Fwx.TAG_LENGTH + 1) damaged("file is truncated")
        val final: Boolean
        var lookaheadLength = 0
        if (currentLength < current.size) {
            final = true
        } else {
            lookaheadLength = Fwx.readUpTo(input, lookahead, 0, lookahead.size)
            final = lookaheadLength == 0
        }
        if (counter > 0xFFFFFFFFL) tooLarge("chunk counter would wrap")
        val nonce = ByteArray(12)
        System.arraycopy(header.noncePrefix, 0, nonce, 0, Fwx.NONCE_PREFIX_LENGTH)
        Fwx.putU32(nonce, Fwx.NONCE_PREFIX_LENGTH, counter)
        nonce[11] = if (final) 1 else 0
        val n = try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(current, 0, currentLength, plain, 0)
        } catch (e: GeneralSecurityException) {
            plain.fill(0)
            damaged("chunk $counter failed authentication")
        }
        // Only now, after the tag verified, is the chunk released to the framing parser.
        plainLength = n
        plainAt = 0
        if (final) {
            sawFinal = true
        } else {
            val t = current
            current = lookahead
            lookahead = t
            currentLength = lookaheadLength
            counter++
        }
        return true
    }

    private inline fun <T> guarded(block: () -> T): T {
        check(!failed) { "the reader failed earlier" }
        try {
            return block()
        } catch (e: Throwable) {
            failed = true
            wipe()
            throw e
        }
    }

    private fun payload(detail: String): Nothing = throw FwxException(FwxError.MALFORMED_PAYLOAD, detail)
    private fun damaged(detail: String): Nothing = throw FwxException(FwxError.DAMAGED, detail)
    private fun tooLarge(detail: String): Nothing = throw FwxException(FwxError.TOO_LARGE, detail)

    private inner class EntryStream(var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            check(!closed) { "the reader is closed" }
            if (open !== this || remaining == 0L) return -1
            return guarded {
                if (plainAt == plainLength && !refill()) payload("stream ends inside entry data")
                val n = minOf(len.toLong(), remaining, (plainLength - plainAt).toLong()).toInt()
                System.arraycopy(plain, plainAt, b, off, n)
                plainAt += n
                remaining -= n
                consumed += n
                n
            }
        }

        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val scratch = ByteArray(minOf(n, 8192L).toInt())
            var skipped = 0L
            while (skipped < n) {
                val r = read(scratch, 0, minOf(n - skipped, scratch.size.toLong()).toInt())
                if (r < 0) break
                skipped += r
            }
            scratch.fill(0)
            return skipped
        }

        override fun available(): Int =
            if (open !== this || failed) 0 else minOf(remaining, (plainLength - plainAt).toLong()).toInt()
    }
}
