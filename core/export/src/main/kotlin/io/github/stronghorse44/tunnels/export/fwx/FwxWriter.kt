// FWX codec 1.2.0, canonical sha256 ad0062560e548d373d2460a7fe51aaf215ddb7b1075410a9fb5b4973af1abfc5 (fieldwork codec/kotlin/src/main/kotlin/fwx/FwxWriter.kt at a4e418d)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streams an FWX v1 bundle into [out] (spec sections 1 to 4). Keys are derived and H | header_mac written in the
 * constructor; then call [entry] once per entry and [finish] once. Memory is one chunk of plaintext plus one sealed
 * chunk, whatever the entry sizes.
 *
 * Any failure poisons the writer: every later call throws IllegalStateException, and the caller deletes the partial
 * output. The caller owns [out] (this class flushes it in [finish] but never closes it) and owns and zeroes the
 * passphrase.
 */
class FwxWriter private constructor(
    private val out: OutputStream,
    appId: String,
    schemaVersion: Long,
    createdMs: Long,
    passphrase: CharArray,
    iterations: Int,
    chunkSize: Int,
    seeds: Seeds,
) {
    /**
     * [schemaVersion] is the app's bundle schema (1..2^32-1); [createdMs] is the device clock at export start. The
     * salt and nonce prefix come fresh from SecureRandom. A passphrase under 12 code points is BAD_PASSPHRASE.
     */
    constructor(
        out: OutputStream,
        appId: String,
        schemaVersion: Long,
        createdMs: Long,
        passphrase: CharArray,
        iterations: Int = Fwx.DEFAULT_ITERATIONS,
        chunkSize: Int = Fwx.DEFAULT_CHUNK_SIZE,
    ) : this(out, appId, schemaVersion, createdMs, passphrase, iterations, chunkSize, Seeds.fresh())

    private val chunkSize: Int
    private val noncePrefix: ByteArray
    private val aad: ByteArray
    private val key: SecretKeySpec
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buffer: ByteArray
    private val sealed: ByteArray
    private var buffered = 0
    private var counter = 0L
    private var streamBytes = 0L
    private var entries = 0
    private val names = FwxNameSet()
    private var poisoned = false
    private var finished = false

    init {
        if (!Fwx.isValidAppId(appId)) header("app_id fails the registry pattern")
        if (schemaVersion < 1 || schemaVersion > 0xFFFFFFFFL) header("schema_version out of range")
        if (createdMs < 0) header("created_ms is negative")
        if (iterations < Fwx.MIN_ITERATIONS || iterations > Fwx.MAX_ITERATIONS) {
            throw FwxException(FwxError.KDF_PARAMS, "iterations out of range")
        }
        if (seeds.salt.size < Fwx.MIN_SALT_LENGTH || seeds.salt.size > Fwx.MAX_SALT_LENGTH) {
            throw FwxException(FwxError.KDF_PARAMS, "salt length out of range")
        }
        require(seeds.noncePrefix.size == Fwx.NONCE_PREFIX_LENGTH)
        if (!Fwx.isValidChunkSize(chunkSize.toLong())) header("chunk_size out of range")

        val keys = FwxKdf.deriveKeys(passphrase, seeds.salt, iterations, forWriter = true)
        try {
            val h = Fwx.buildHeader(appId, schemaVersion, createdMs, iterations, seeds.salt, seeds.noncePrefix, chunkSize)
            val mac = FwxKdf.hmac(keys.hdr, h)
            aad = h + mac
            key = SecretKeySpec(keys.enc, "AES")
        } finally {
            keys.wipe()
        }
        this.chunkSize = chunkSize
        noncePrefix = seeds.noncePrefix
        buffer = ByteArray(chunkSize)
        sealed = ByteArray(chunkSize + Fwx.TAG_LENGTH)
        out.write(aad)
    }

    /**
     * Writes one entry of exactly [length] bytes from [source]. [source] must return end of stream right after
     * [length] bytes; fewer or more poisons the writer. [name] must follow the entry name rule (spec section 4.2)
     * and be unique ignoring ASCII case. The caller closes [source].
     */
    fun entry(name: String, length: Long, source: InputStream) = guarded {
        val nameBytes = Fwx.entryNameBytesOrNull(name)
            ?: throw FwxException(FwxError.MALFORMED_PAYLOAD, "invalid entry name")
        names.add(name)?.let { throw FwxException(FwxError.MALFORMED_PAYLOAD, it) }
        if (entries >= Fwx.MAX_ENTRIES) throw FwxException(FwxError.MALFORMED_PAYLOAD, "too many entries")
        if (length < 0 || length > Fwx.MAX_ENTRY_BYTES) throw FwxException(FwxError.TOO_LARGE, "entry too large")
        if (streamBytes + 11 + nameBytes.size + length + 5 > Fwx.MAX_STREAM_BYTES) {
            throw FwxException(FwxError.TOO_LARGE, "bundle too large")
        }
        val head = ByteArray(11 + nameBytes.size)
        head[0] = 0x01
        Fwx.putU16(head, 1, nameBytes.size)
        System.arraycopy(nameBytes, 0, head, 3, nameBytes.size)
        Fwx.putU64(head, 3 + nameBytes.size, length)
        write(head, 0, head.size)

        var left = length
        while (left > 0) {
            if (buffered == chunkSize) seal(final = false)
            val want = minOf(left, (chunkSize - buffered).toLong()).toInt()
            val r = source.read(buffer, buffered, want)
            if (r < 0) throw FwxException(FwxError.MALFORMED_PAYLOAD, "entry source ended before its length")
            buffered += r
            left -= r
            streamBytes += r
        }
        if (source.read() != -1) throw FwxException(FwxError.MALFORMED_PAYLOAD, "entry source is longer than its length")
        entries++
    }

    /** Writes one entry held in memory. */
    fun entry(name: String, data: ByteArray) = entry(name, data.size.toLong(), ByteArrayInputStream(data))

    /** Writes the end record, seals the final chunk and flushes [out]. */
    fun finish() = guarded {
        val end = ByteArray(5)
        Fwx.putU32(end, 1, entries.toLong())
        write(end, 0, end.size)
        seal(final = true)
        out.flush()
        finished = true
        buffer.fill(0)
    }

    /**
     * Appends plaintext. A full buffer is sealed as non-final only when another byte must follow it, so the final
     * chunk is never empty (spec section 2.1).
     */
    private fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        val end = off + len
        while (at < end) {
            if (buffered == chunkSize) seal(final = false)
            val n = minOf(end - at, chunkSize - buffered)
            System.arraycopy(b, at, buffer, buffered, n)
            buffered += n
            at += n
            streamBytes += n
        }
    }

    private fun seal(final: Boolean) {
        if (counter > 0xFFFFFFFFL) throw FwxException(FwxError.TOO_LARGE, "chunk counter would wrap")
        val nonce = ByteArray(12)
        System.arraycopy(noncePrefix, 0, nonce, 0, Fwx.NONCE_PREFIX_LENGTH)
        Fwx.putU32(nonce, Fwx.NONCE_PREFIX_LENGTH, counter)
        nonce[11] = if (final) 1 else 0
        val n = try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(buffer, 0, buffered, sealed, 0)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("AES-GCM is unavailable", e)
        }
        out.write(sealed, 0, n)
        counter++
        buffered = 0
    }

    /**
     * The first failure poisons the writer and propagates as it is: an [FwxException] for a rule, or the
     * destination's or source's own IOException, unwrapped. Any later call is an IllegalStateException.
     */
    private inline fun guarded(block: () -> Unit) {
        check(!poisoned) { "the writer failed earlier; delete the partial output" }
        check(!finished) { "finish() was already called" }
        try {
            block()
        } catch (e: Throwable) {
            poisoned = true
            buffer.fill(0)
            throw e
        }
    }

    /**
     * Salt and nonce prefix. Production code only ever gets [fresh]; the constructor that takes fixed values is
     * private and reached only by the JVM tests, through reflection, to build the byte-exact vectors.
     */
    private class Seeds(val salt: ByteArray, val noncePrefix: ByteArray) {
        companion object {
            /** One CSPRNG, 16 bytes of salt first, then 7 bytes of nonce prefix (spec section 1). */
            fun fresh(): Seeds {
                val random = SecureRandom()
                val salt = ByteArray(Fwx.DEFAULT_SALT_LENGTH).also(random::nextBytes)
                val nonce = ByteArray(Fwx.NONCE_PREFIX_LENGTH).also(random::nextBytes)
                return Seeds(salt, nonce)
            }
        }
    }

    private fun header(detail: String): Nothing = throw FwxException(FwxError.MALFORMED_HEADER, detail)
}
