// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/TestSupport.kt at a4e418d (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test-only helpers: hex, generators, the spec's vector inputs, the crafted-stream hook and JSON string quoting. */
object T {
    const val PASSPHRASE = "correct horse battery staple"
    const val CREATED_MS = 1791028800000L
    val SALT16 = ByteArray(16) { it.toByte() }
    val SALT32 = ByteArray(32) { it.toByte() }
    val NONCE = hex("a0a1a2a3a4a5a6")
    const val V4_PASSPHRASE = "Grüße aus Köln 🔑"
    const val V5_PASSPHRASE = "Grüße aus Köln 🔑"

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(digits[(x.toInt() shr 4) and 15]).append(digits[x.toInt() and 15])
        return sb.toString()
    }

    fun sha256(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

    fun pattern(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    /** Streams pattern(n) without holding it, for the multi-chunk tests. */
    class PatternStream(private val n: Long) : InputStream() {
        private var at = 0L
        override fun read(): Int = if (at < n) ((at++ % 251).toInt()) else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= n) return -1
            val k = minOf(len.toLong(), n - at).toInt()
            for (i in 0 until k) b[off + i] = ((at + i) % 251).toByte()
            at += k
            return k
        }
    }

    /** An entry of a vector: its bytes come from a generator described in the JSON. */
    class EntrySpec(val name: String, val length: Long, val kind: String, val hexData: String = "", val fill: Int = 0) {
        fun stream(): InputStream = when (kind) {
            "hex" -> ByteArrayInputStream(hex(hexData))
            "pattern" -> PatternStream(length)
            "fill" -> ByteArrayInputStream(ByteArray(length.toInt()) { fill.toByte() })
            else -> error(kind)
        }

        fun bytes(): ByteArray = stream().readBytes()

        fun json(): String = when (kind) {
            "hex" -> """{"hex": "$hexData"}"""
            "pattern" -> """{"pattern": $length}"""
            else -> """{"fill_hex": "${hex(byteArrayOf(fill.toByte()))}", "n": $length}"""
        }
    }

    class VectorSpec(
        val id: String,
        val description: String,
        val passphrase: String,
        val appId: String,
        val schema: Long,
        val iterations: Int,
        val salt: ByteArray,
        val chunkSize: Int,
        val entries: List<EntrySpec>,
        val commitFile: Boolean,
    )

    private val V1_ENTRIES = listOf(
        EntrySpec("manifest.json", 28, "hex", hex("""{"app":"tunnels","schema":1}""".toByteArray())),
        EntrySpec("empty.bin", 0, "hex", ""),
        EntrySpec("data/pattern.bin", 10000, "pattern"),
    )

    /** Section 9.3, exactly as the spec lists them. */
    val VECTORS = listOf(
        VectorSpec("V1", "common inputs, three entries", PASSPHRASE, "tunnels", 1, 600_000, SALT16, 4096, V1_ENTRIES, true),
        VectorSpec("V2", "no entries", PASSPHRASE, "tunnels", 1, 600_000, SALT16, 4096, emptyList(), true),
        VectorSpec(
            "V3", "L = 8192 exactly, final chunk full", PASSPHRASE, "tunnels", 1, 600_000, SALT16, 4096,
            listOf(EntrySpec("x", 8175, "fill", fill = 0x5a)), true,
        ),
        VectorSpec("V4", "non-ASCII, non-BMP passphrase (NFC)", V4_PASSPHRASE, "tunnels", 1, 600_000, SALT16, 4096, V1_ENTRIES, true),
        VectorSpec("V5", "NFD form of V4's passphrase; file identical to V4", V5_PASSPHRASE, "tunnels", 1, 600_000, SALT16, 4096, V1_ENTRIES, true),
        VectorSpec(
            "V6", "1 MiB chunks, 3 MiB entry; file not committed", PASSPHRASE, "tunnels", 1, 600_000, SALT16, 1 shl 20,
            listOf(EntrySpec("big.bin", 3145729, "pattern")), false,
        ),
        VectorSpec("V7", "app lumen, schema 7, 600001 iterations, 32-byte salt", PASSPHRASE, "lumen", 7, 600_001, SALT32, 4096, V1_ENTRIES, true),
    )

    fun vector(id: String) = VECTORS.first { it.id == id }

    /**
     * A writer with a fixed salt and nonce prefix, for byte-exact vectors only. The codec has no such entry point:
     * this reaches its private constructor through reflection, which production code never does.
     */
    fun fixedWriter(
        out: java.io.OutputStream, appId: String, schema: Long, createdMs: Long, passphrase: CharArray,
        iterations: Int, chunkSize: Int, salt: ByteArray, noncePrefix: ByteArray,
    ): FwxWriter {
        val seedsClass = Class.forName(FwxWriter::class.java.name + "\$Seeds")
        val seedsConstructor = seedsClass.getDeclaredConstructor(ByteArray::class.java, ByteArray::class.java)
        seedsConstructor.isAccessible = true
        val seeds = seedsConstructor.newInstance(salt.copyOf(), noncePrefix.copyOf())
        val constructor = FwxWriter::class.java.getDeclaredConstructor(
            java.io.OutputStream::class.java, String::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, CharArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, seedsClass,
        )
        constructor.isAccessible = true
        try {
            return constructor.newInstance(out, appId, schema, createdMs, passphrase, iterations, chunkSize, seeds)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Writes a vector with the fixed salt and nonce prefix. */
    fun pack(v: VectorSpec, out: java.io.OutputStream) {
        val w = fixedWriter(
            out, v.appId, v.schema, CREATED_MS, v.passphrase.toCharArray(), v.iterations, v.chunkSize, v.salt, NONCE,
        )
        for (e in v.entries) e.stream().use { w.entry(e.name, e.length, it) }
        w.finish()
    }

    fun pack(v: VectorSpec): ByteArray = ByteArrayOutputStream().also { pack(v, it) }.toByteArray()

    /** The plaintext stream P of a list of entries (section 4.1). */
    fun stream(entries: List<Pair<ByteArray, ByteArray>>, count: Int = entries.size): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) out.write(record(name, data.size.toLong()) + data)
        out.write(end(count))
        return out.toByteArray()
    }

    fun record(name: ByteArray, dataLength: Long): ByteArray {
        val b = ByteArray(11 + name.size)
        b[0] = 1
        Fwx.putU16(b, 1, name.size)
        System.arraycopy(name, 0, b, 3, name.size)
        Fwx.putU64(b, 3 + name.size, dataLength)
        return b
    }

    fun end(count: Int): ByteArray = ByteArray(5).also { Fwx.putU32(it, 1, count.toLong()) }

    fun streamOf(v: VectorSpec): ByteArray = stream(v.entries.map { it.name.toByteArray() to it.bytes() })

    /**
     * The spec's test-only sealRawStream(P, finalFlags): V1's header and keys, P cut at chunk_size, chunk i sealed
     * with flag finalFlags[i] (default: only the last chunk final).
     */
    fun sealRawStream(p: ByteArray, finalFlags: List<Boolean>? = null): ByteArray {
        val v = vector("V1")
        val h = Fwx.buildHeader(v.appId, v.schema, CREATED_MS, v.iterations, v.salt, NONCE, v.chunkSize)
        val keys = FwxKdf.deriveKeys(v.passphrase.toCharArray(), v.salt, v.iterations, forWriter = false)
        val mac = FwxKdf.hmac(keys.hdr, h)
        val aad = h + mac
        val out = ByteArrayOutputStream()
        out.write(aad)
        val n = maxOf(1, (p.size + v.chunkSize - 1) / v.chunkSize)
        val flags = finalFlags ?: List(n) { it == n - 1 }
        require(flags.size == n)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        for (i in 0 until n) {
            val nonce = NONCE + ByteArray(4).also { Fwx.putU32(it, 0, i.toLong()) } + byteArrayOf(if (flags[i]) 1 else 0)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            out.write(cipher.doFinal(p, i * v.chunkSize, minOf(v.chunkSize, p.size - i * v.chunkSize)))
        }
        keys.wipe()
        return out.toByteArray()
    }

    /** Section 9.4 crafted streams C1 to C11 (and C11b), C13 to C15, sealed under V1's keys. Python's test builds the same bytes. */
    fun crafted(): List<Pair<String, ByteArray>> {
        val v1 = streamOf(vector("V1"))
        fun one(name: ByteArray) = stream(listOf(name to ByteArray(0)))
        fun pair(a: String, b: String) = stream(listOf(a.toByteArray() to byteArrayOf(1), b.toByteArray() to byteArrayOf(2)))
        val c6 = record("a".toByteArray(), 100) + ByteArray(10) { 0x41 } + end(1)
        val c9 = v1.copyOf().also { Fwx.putU32(it, it.size - 4, 4) }
        val c11 = record("big".toByteArray(), (1L shl 36) + 1) + end(1)
        return listOf(
            "C1" to sealRawStream(v1, listOf(false, false, false)),
            "C2" to sealRawStream(v1, listOf(true, false, true)),
            "C3" to sealRawStream(stream(listOf("a".toByteArray() to byteArrayOf(1), "a".toByteArray() to byteArrayOf(2)))),
            "C4" to sealRawStream(stream(listOf("A.bin".toByteArray() to byteArrayOf(1), "a.bin".toByteArray() to byteArrayOf(2)))),
            "C5a" to sealRawStream(one("../x".toByteArray())),
            "C5b" to sealRawStream(one("/x".toByteArray())),
            "C5c" to sealRawStream(one("a//b".toByteArray())),
            "C5d" to sealRawStream(one("a\\b".toByteArray())),
            "C5e" to sealRawStream(one(".".toByteArray())),
            "C5f" to sealRawStream(one(ByteArray(256) { 'a'.code.toByte() })),
            "C5g" to sealRawStream(one(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()))),
            "C6" to sealRawStream(c6),
            "C7" to sealRawStream(v1.copyOf(v1.size - 5)),
            "C8" to sealRawStream(v1 + byteArrayOf(0)),
            "C9" to sealRawStream(c9),
            "C10" to sealRawStream(byteArrayOf(2, 0, 0, 0, 0, 0)),
            "C11" to sealRawStream(c11),
            "C11b" to sealRawStream(record("big".toByteArray(), -1L) + end(1)),
            "C13a" to sealRawStream(pair("a", "a/b")),
            "C13b" to sealRawStream(pair("a/b", "a")),
            "C13c" to sealRawStream(pair("A", "a/b")),
            "C14" to sealRawStream(pair("ab", "a/b")),
            "C15" to c15(),
        )
    }

    /** 4113 distinct 255-byte names: the last one takes the total past 1 MiB (spec section 4.3). */
    fun c15(): ByteArray {
        val entries = (0 until 4113).map { i -> (String.format("%05d", i) + "n".repeat(250)).toByteArray() to ByteArray(0) }
        return sealRawStream(stream(entries))
    }

    /** Crafted files not committed (over 1 MiB): built here and checked against their JSON hash. */
    val UNCOMMITTED = setOf("C15")

    /** A base file for cases.txt: the committed copy, or the generated one for [UNCOMMITTED]. */
    fun base(id: String): ByteArray = if (id in UNCOMMITTED) c15() else resource("$id.fwx")

    /** The exact stdout of `fwx.py --list` for a verified bundle. */
    fun listOutput(appId: String, appName: String, schema: Long, iterations: Int, saltLength: Int, chunkSize: Int,
                   entries: List<Triple<Long, String, String>>, streamLength: Long): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(CREATED_MS))
        val sb = StringBuilder()
        sb.append("FWX v1 bundle, header verified\n")
        sb.append("app_id          $appId ($appName)\n")
        sb.append("schema_version  $schema\n")
        sb.append("created         $time (created_ms $CREATED_MS)\n")
        sb.append("kdf             pbkdf2-hmac-sha256, $iterations iterations, $saltLength-byte salt\n")
        sb.append("chunk_size      $chunkSize\n")
        entries.forEachIndexed { i, (size, sha, name) -> sb.append("$i\t$size\t$sha\t$name\n") }
        sb.append("OK ${entries.size} entries, $streamLength plaintext bytes\n")
        return sb.toString()
    }

    /** Vector files: the classpath (fwx-v1/ under test resources), or the directory in -Dfwx.vectors. */
    fun resource(name: String): ByteArray {
        System.getProperty("fwx.vectors")?.let { return File(it, name).readBytes() }
        val s = T::class.java.getResourceAsStream("/fwx-v1/$name") ?: error("missing test vector fwx-v1/$name")
        return s.use { it.readBytes() }
    }

    fun resourceText(name: String) = String(resource(name), Charsets.UTF_8)

    /** Counts bytes the codec pulls from the file, to prove what was and was not read. */
    class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
    }

    /** Opens [file] and reads every entry to the end. Returns null on a verified end, else the failure code. */
    fun openAll(
        file: ByteArray,
        passphrase: CharArray = PASSPHRASE.toCharArray(),
        app: String? = null,
        schema: LongRange? = null,
        max: Long = Fwx.MAX_STREAM_BYTES,
        counter: CountingStream? = null,
        delivered: MutableList<String>? = null,
    ): FwxError? {
        val input = counter ?: CountingStream(ByteArrayInputStream(file))
        return try {
            val r = FwxReader(input, passphrase, app, schema, max)
            while (true) {
                val e = r.next() ?: break
                delivered?.add(e.name)
                e.stream.readBytes()
            }
            null
        } catch (e: FwxException) {
            e.code
        }
    }

    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 || c.code > 0x7e -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
