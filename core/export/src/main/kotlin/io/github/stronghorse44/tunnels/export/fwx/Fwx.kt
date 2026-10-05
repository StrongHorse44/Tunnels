// FWX codec 1.2.0, canonical sha256 e5a99cd6a7230d6a079da75bc72844a7712f7387f95f5e1ddb1e7f8c912505d8 (fieldwork codec/kotlin/src/main/kotlin/fwx/Fwx.kt at a4e418d)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.InputStream
import java.security.MessageDigest

/**
 * A parsed FWX v1 header (spec section 1). From [Fwx.readHeader] every value is unverified: anyone who can write the
 * file can set it. From an open [FwxReader] the header MAC has been checked and [verified] is true.
 */
class FwxHeader internal constructor(
    val appId: String,
    val schemaVersion: Long,
    val createdMs: Long,
    val kdfIterations: Int,
    val saltLength: Int,
    val chunkSize: Int,
    val headerLength: Int,
    /** The file length when the caller passed it to [Fwx.readHeader], else null. */
    val fileLength: Long?,
    val verified: Boolean,
    internal val salt: ByteArray,
    internal val noncePrefix: ByteArray,
    /** H, bytes [0, header_len). */
    internal val bytes: ByteArray,
    internal val mac: ByteArray,
) {
    internal fun asVerified(): FwxHeader = FwxHeader(
        appId, schemaVersion, createdMs, kdfIterations, saltLength, chunkSize, headerLength, fileLength, true,
        salt, noncePrefix, bytes, mac,
    )
}

/** Constants, format detection and passphrase-free header reading for FWX v1. */
object Fwx {
    /** Version of this codec source. Copies in app repos name it in their header comment (codec/README.md). */
    const val CODEC_VERSION = "1.2.0"

    const val FORMAT_VERSION = 1
    const val KDF_NAME = "pbkdf2-hmac-sha256"
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000
    const val DEFAULT_SALT_LENGTH = 16
    const val MIN_SALT_LENGTH = 16
    const val MAX_SALT_LENGTH = 64
    const val NONCE_PREFIX_LENGTH = 7
    const val DEFAULT_CHUNK_SIZE = 1 shl 20
    const val MIN_CHUNK_SIZE = 1 shl 12
    const val MAX_CHUNK_SIZE = 1 shl 22
    const val MAC_LENGTH = 32
    const val TAG_LENGTH = 16
    const val MIN_HEADER_LENGTH = 60
    const val MAX_HEADER_LENGTH = 170
    const val MAX_STREAM_BYTES = 1L shl 36
    const val MAX_ENTRY_BYTES = 1L shl 36
    const val MAX_ENTRIES = 100_000
    const val MAX_NAME_LENGTH = 255
    const val MAX_TOTAL_NAME_BYTES = 1 shl 20
    const val MAX_APP_ID_LENGTH = 32
    const val MAX_KDF_NAME_LENGTH = 32
    const val MIN_WRITER_PASSPHRASE_CODE_POINTS = 12
    const val MAX_PASSPHRASE_BYTES = 1024
    const val LEGACY_MAGIC = "TSNAPE1"

    private val MAGIC = byteArrayOf(0x89.toByte(), 0x46, 0x57, 0x58, 0x0D, 0x0A, 0x1A, 0x0A)
    private val LEGACY = LEGACY_MAGIC.toByteArray(Charsets.US_ASCII)
    private val KDF_NAME_BYTES = KDF_NAME.toByteArray(Charsets.US_ASCII)

    /** The magic bytes `89 46 57 58 0D 0A 1A 0A`, as a fresh copy. */
    fun magic(): ByteArray = MAGIC.copyOf()

    enum class Format { FWX, LEGACY, UNKNOWN }

    /**
     * Classifies a file from its first bytes (pass at least 8): FWX magic, the legacy Tunnels `TSNAPE1` magic, or
     * neither (spec section 11). The Tunnels importer uses this to route a legacy file to its old reader.
     */
    fun detect(prefix: ByteArray): Format = when {
        prefix.size >= MAGIC.size && startsWith(prefix, MAGIC) -> Format.FWX
        prefix.size >= LEGACY.size && startsWith(prefix, LEGACY) -> Format.LEGACY
        else -> Format.UNKNOWN
    }

    /**
     * Steps 1 to 4 of spec section 5.1 without a passphrase: reads at most header_len + 32 (so at most 202) bytes
     * and never the body. The result is unverified (spec section 6). Throws [FwxException] with NOT_AN_EXPORT,
     * LEGACY, UNSUPPORTED_VERSION, MALFORMED_HEADER, UNSUPPORTED_KDF, KDF_PARAMS or DAMAGED.
     */
    fun readHeader(input: InputStream, fileLength: Long? = null): FwxHeader = parseHeader(input, fileLength)

    internal fun parseHeader(input: InputStream, fileLength: Long?): FwxHeader {
        val first = ByteArray(12)
        val got = readUpTo(input, first, 0, first.size)
        if (got < MAGIC.size || !startsWith(first, MAGIC)) {
            if (got >= LEGACY.size && startsWith(first, LEGACY)) {
                throw FwxException(FwxError.LEGACY, "legacy Tunnels TSNAPE1 export")
            }
            throw FwxException(FwxError.NOT_AN_EXPORT, "magic does not match")
        }
        if (got < first.size) throw FwxException(FwxError.DAMAGED, "file ends inside the header")
        val version = u16(first, 8)
        if (version != FORMAT_VERSION) {
            throw FwxException(FwxError.UNSUPPORTED_VERSION, "format_version $version")
        }
        val headerLength = u16(first, 10)
        if (headerLength < MIN_HEADER_LENGTH || headerLength > MAX_HEADER_LENGTH) {
            throw FwxException(FwxError.MALFORMED_HEADER, "header_len $headerLength out of range")
        }
        val h = ByteArray(headerLength)
        System.arraycopy(first, 0, h, 0, first.size)
        val mac = ByteArray(MAC_LENGTH)
        if (readUpTo(input, h, first.size, headerLength - first.size) != headerLength - first.size ||
            readUpTo(input, mac, 0, MAC_LENGTH) != MAC_LENGTH
        ) {
            throw FwxException(FwxError.DAMAGED, "file ends inside the header")
        }

        val p = HeaderCursor(h)
        val appIdLength = p.u8()
        if (appIdLength == 0 || appIdLength > MAX_APP_ID_LENGTH) malformed("app_id_len $appIdLength")
        val appIdBytes = p.bytes(appIdLength)
        if (!isValidAppId(appIdBytes)) malformed("app_id fails the registry pattern")
        val appId = String(appIdBytes, Charsets.US_ASCII)
        val schema = p.u32()
        if (schema == 0L) malformed("schema_version 0")
        val created = p.u64()
        if (created < 0) malformed("created_ms has the top bit set")
        val kdfNameLength = p.u8()
        if (kdfNameLength == 0 || kdfNameLength > MAX_KDF_NAME_LENGTH) malformed("kdf_name_len $kdfNameLength")
        if (!p.bytes(kdfNameLength).contentEquals(KDF_NAME_BYTES)) {
            throw FwxException(FwxError.UNSUPPORTED_KDF, "unknown kdf_name")
        }
        val iterations = p.u32()
        if (iterations < MIN_ITERATIONS) throw FwxException(FwxError.KDF_PARAMS, "iterations below floor")
        if (iterations > MAX_ITERATIONS) throw FwxException(FwxError.KDF_PARAMS, "iterations above ceiling")
        val saltLength = p.u8()
        if (saltLength < MIN_SALT_LENGTH || saltLength > MAX_SALT_LENGTH) {
            throw FwxException(FwxError.KDF_PARAMS, "salt_len $saltLength out of range")
        }
        val salt = p.bytes(saltLength)
        val noncePrefix = p.bytes(NONCE_PREFIX_LENGTH)
        val chunkSize = p.u32()
        if (!isValidChunkSize(chunkSize)) malformed("chunk_size $chunkSize")
        if (p.at != headerLength) malformed("header_len $headerLength does not match parsed length ${p.at}")

        return FwxHeader(
            appId, schema, created, iterations.toInt(), saltLength, chunkSize.toInt(), headerLength, fileLength,
            false, salt, noncePrefix, h, mac,
        )
    }

    /** Builds H (spec section 1). The caller has validated every value. */
    internal fun buildHeader(
        appId: String,
        schemaVersion: Long,
        createdMs: Long,
        iterations: Int,
        salt: ByteArray,
        noncePrefix: ByteArray,
        chunkSize: Int,
    ): ByteArray {
        val a = appId.length
        val length = 42 + a + KDF_NAME_BYTES.size + salt.size
        val h = ByteArray(length)
        var at = 0
        System.arraycopy(MAGIC, 0, h, at, MAGIC.size); at += MAGIC.size
        putU16(h, at, FORMAT_VERSION); at += 2
        putU16(h, at, length); at += 2
        h[at++] = a.toByte()
        System.arraycopy(appId.toByteArray(Charsets.US_ASCII), 0, h, at, a); at += a
        putU32(h, at, schemaVersion); at += 4
        putU64(h, at, createdMs); at += 8
        h[at++] = KDF_NAME_BYTES.size.toByte()
        System.arraycopy(KDF_NAME_BYTES, 0, h, at, KDF_NAME_BYTES.size); at += KDF_NAME_BYTES.size
        putU32(h, at, iterations.toLong()); at += 4
        h[at++] = salt.size.toByte()
        System.arraycopy(salt, 0, h, at, salt.size); at += salt.size
        System.arraycopy(noncePrefix, 0, h, at, noncePrefix.size); at += noncePrefix.size
        putU32(h, at, chunkSize.toLong()); at += 4
        check(at == length)
        return h
    }

    internal fun isValidAppId(b: ByteArray): Boolean {
        if (b.isEmpty() || b.size > MAX_APP_ID_LENGTH) return false
        if (b[0] < 'a'.code.toByte() || b[0] > 'z'.code.toByte()) return false
        for (c in b) {
            val ok = (c >= 'a'.code.toByte() && c <= 'z'.code.toByte()) ||
                (c >= '0'.code.toByte() && c <= '9'.code.toByte()) || c == '-'.code.toByte()
            if (!ok) return false
        }
        return true
    }

    internal fun isValidAppId(s: String): Boolean =
        s.all { it.code < 0x80 } && isValidAppId(s.toByteArray(Charsets.US_ASCII))

    internal fun isValidChunkSize(cs: Long): Boolean =
        cs >= MIN_CHUNK_SIZE && cs <= MAX_CHUNK_SIZE && (cs and (cs - 1)) == 0L

    /**
     * Entry name rule (spec section 4.2): 1..255 ASCII bytes, segments of `[A-Za-z0-9._-]+` joined by single
     * slashes, no segment `.` or `..`. Returns the name, or null when it breaks the rule.
     */
    internal fun entryNameOrNull(b: ByteArray): String? {
        if (b.isEmpty() || b.size > MAX_NAME_LENGTH) return null
        var segmentStart = 0
        for (i in 0..b.size) {
            if (i == b.size || b[i] == '/'.code.toByte()) {
                if (!isValidSegment(b, segmentStart, i)) return null
                segmentStart = i + 1
            } else if (!isNameByte(b[i])) {
                return null
            }
        }
        return String(b, Charsets.US_ASCII)
    }

    /** The ASCII bytes of [name] when it is a valid entry name, else null. */
    internal fun entryNameBytesOrNull(name: String): ByteArray? {
        if (name.any { it.code >= 0x80 }) return null
        val b = name.toByteArray(Charsets.US_ASCII)
        return if (entryNameOrNull(b) != null) b else null
    }

    /** Folds ASCII A-Z to a-z; names are ASCII, so nothing else folds. */
    internal fun foldCase(name: String): String {
        val c = name.toCharArray()
        for (i in c.indices) if (c[i] in 'A'..'Z') c[i] = c[i] + 32
        return String(c)
    }

    private fun isValidSegment(b: ByteArray, from: Int, to: Int): Boolean {
        val n = to - from
        if (n == 0) return false
        if (n == 1 && b[from] == '.'.code.toByte()) return false
        if (n == 2 && b[from] == '.'.code.toByte() && b[from + 1] == '.'.code.toByte()) return false
        return true
    }

    private fun isNameByte(c: Byte): Boolean =
        (c >= 'A'.code.toByte() && c <= 'Z'.code.toByte()) || (c >= 'a'.code.toByte() && c <= 'z'.code.toByte()) ||
            (c >= '0'.code.toByte() && c <= '9'.code.toByte()) ||
            c == '.'.code.toByte() || c == '_'.code.toByte() || c == '-'.code.toByte()

    private fun malformed(detail: String): Nothing = throw FwxException(FwxError.MALFORMED_HEADER, detail)

    private fun startsWith(b: ByteArray, prefix: ByteArray): Boolean {
        for (i in prefix.indices) if (b[i] != prefix[i]) return false
        return true
    }

    /** Reads until [len] bytes or end of stream; returns the count. Never reads past [len]. */
    internal fun readUpTo(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    internal fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    internal fun u32(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** u64 as a Long; a value with the top bit set comes back negative. */
    internal fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    internal fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte()
        b[at + 1] = v.toByte()
    }

    internal fun putU32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * (3 - i))).toByte()
    }

    internal fun putU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * (7 - i))).toByte()
    }

    /** Reads fields of H in order; a field that would run past header_len is MALFORMED_HEADER. */
    private class HeaderCursor(private val h: ByteArray) {
        var at = 12

        private fun need(n: Int) {
            if (at + n > h.size) malformed("a field runs past header_len")
        }

        fun u8(): Int {
            need(1)
            return h[at++].toInt() and 0xFF
        }

        fun u32(): Long {
            need(4)
            return Fwx.u32(h, at).also { at += 4 }
        }

        fun u64(): Long {
            need(8)
            return Fwx.u64(h, at).also { at += 8 }
        }

        fun bytes(n: Int): ByteArray {
            need(n)
            return h.copyOfRange(at, at + n).also { at += n }
        }
    }
}

/**
 * The names of one bundle, for the rules of spec sections 4.2 and 4.3, shared by writer and reader: names are
 * unique ignoring ASCII case, no name is a folder of another (`a` with `a/b`, in either order; whole segments only,
 * so `ab` with `a/b` is fine), and all names together are at most 1 MiB. Both sets hold 16-byte SHA-256 digests of
 * the lowercased strings, never the strings, so memory stays bounded; a digest collision only refuses a valid name.
 */
internal class FwxNameSet {
    private data class Digest(val high: Long, val low: Long)

    private val names = HashSet<Digest>()
    private val folders = HashSet<Digest>()
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private var totalBytes = 0L

    /** Adds a valid entry name. Returns null, or why the name is refused (then nothing is added). */
    fun add(name: String): String? {
        if (totalBytes + name.length > Fwx.MAX_TOTAL_NAME_BYTES) return "entry names exceed 1 MiB in total"
        val key = Fwx.foldCase(name)
        val self = digest(key, key.length)
        if (self in names) return "duplicate entry name"
        if (self in folders) return "entry name is a folder of another entry"
        val prefixes = ArrayList<Digest>()
        var slash = key.indexOf('/')
        while (slash >= 0) {
            val prefix = digest(key, slash)
            if (prefix in names) return "entry name is inside another entry's name as a folder"
            prefixes.add(prefix)
            slash = key.indexOf('/', slash + 1)
        }
        totalBytes += name.length
        names.add(self)
        folders.addAll(prefixes)
        return null
    }

    /** First 16 bytes of SHA-256 over the first [length] ASCII characters of [key]. */
    private fun digest(key: String, length: Int): Digest {
        val bytes = ByteArray(length) { key[it].code.toByte() }
        val d = sha256.digest(bytes)
        return Digest(Fwx.u64(d, 0), Fwx.u64(d, 8))
    }
}
