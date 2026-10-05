// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/CasesTest.kt at a4e418d (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Section 9.4: every tamper and crafted case in codec/vectors/fwx-v1/cases.txt fails with its exact code. */
class CasesTest {
    class Case(
        val id: String,
        val base: String,
        val ops: String,
        val passphrase: String,
        val app: String,
        val schema: String,
        val max: String,
        /** null: the file must open (C14). */
        val expected: FwxError?,
        /** Entries next() must return before the failure, or null when not checked. */
        val delivered: Int?,
    )

    companion object {
        fun cases(): List<Case> = T.resourceText("cases.txt").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .map { Case(it[0], it[1], it[2], it[3], it[4], it[5], it[6], if (it[7] == "OK") null else FwxError.valueOf(it[7]), it[8].toIntOrNull()) }

        fun apply(base: ByteArray, ops: String): ByteArray {
            var b = base
            if (ops == "-") return b
            for (op in ops.split(',')) {
                val p = op.split(':')
                b = when (p[0]) {
                    "flip" -> b.copyOf().also { it[p[1].toInt()] = (it[p[1].toInt()].toInt() xor (1 shl p[2].toInt())).toByte() }
                    "set" -> b.copyOf().also { val v = T.hex(p[2]); System.arraycopy(v, 0, it, p[1].toInt(), v.size) }
                    "cut" -> b.copyOfRange(0, p[1].toInt()) + b.copyOfRange(p[1].toInt() + p[2].toInt(), b.size)
                    "trunc" -> b.copyOf(p[1].toInt())
                    "append" -> b + T.hex(p[1])
                    "dup" -> {
                        val src = b.copyOfRange(p[1].toInt(), p[1].toInt() + p[2].toInt())
                        b.copyOfRange(0, p[3].toInt()) + src + b.copyOfRange(p[3].toInt(), b.size)
                    }
                    else -> error("unknown op $op")
                }
            }
            return b
        }

        fun passphrase(spec: String): CharArray = when {
            spec == "-" -> T.PASSPHRASE.toCharArray()
            spec.startsWith("utf8:") -> String(T.hex(spec.substring(5)), Charsets.UTF_8).toCharArray()
            spec.startsWith("utf16:") -> {
                val b = T.hex(spec.substring(6))
                CharArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF shl 8) or (b[2 * it + 1].toInt() and 0xFF)).toChar() }
            }
            else -> error(spec)
        }
    }

    /** Codes decided from the header alone: no key derivation may have started. */
    private val beforeKdf = setOf(
        FwxError.NOT_AN_EXPORT, FwxError.LEGACY, FwxError.UNSUPPORTED_VERSION, FwxError.MALFORMED_HEADER,
        FwxError.UNSUPPORTED_KDF, FwxError.KDF_PARAMS, FwxError.WRONG_APP, FwxError.SCHEMA_TOO_NEW,
        FwxError.SCHEMA_TOO_OLD, FwxError.BAD_PASSPHRASE,
    )

    /** A passphrase that fails BAD_PASSPHRASE as soon as key derivation looks at it. */
    private val unusable = "\uD800".toCharArray()

    @Test
    fun everyCaseFailsWithItsCode() {
        val all = cases()
        assertEquals(62, all.size)
        for (c in all) {
            val file = apply(T.base(c.base), c.ops)
            val counter = T.CountingStream(ByteArrayInputStream(file))
            val delivered = ArrayList<String>()
            val app = c.app.takeIf { it != "-" }
            val schema = c.schema.takeIf { it != "-" }?.split('-')?.let { it[0].toLong()..it[1].toLong() }
            val max = c.max.takeIf { it != "-" }?.toLong() ?: Fwx.MAX_STREAM_BYTES
            val code = T.openAll(file, passphrase(c.passphrase), app, schema, max, counter, delivered)
            assertEquals(c.id, c.expected, code)
            c.delivered?.let { assertEquals("${c.id}: entries delivered", it, delivered.size) }
            if (code in beforeKdf && code != FwxError.BAD_PASSPHRASE) {
                // Same code with a passphrase key derivation would reject: the KDF was never reached.
                assertEquals("${c.id}: KDF reached", code, T.openAll(file, unusable, app, schema, max))
            }
            if (code in beforeKdf || code == FwxError.WRONG_PASSPHRASE) {
                // No chunk byte was read: at most H and header_mac.
                assertTrue("${c.id}: read ${counter.count} bytes", counter.count <= 202)
                assertTrue(c.id, delivered.isEmpty())
            }
        }
    }

    @Test
    fun c15MatchesItsHash() {
        val json = T.resourceText("C15.json")
        val bytes = T.c15()
        assertTrue(json.contains("\"file_sha256\": \"${T.sha256(bytes)}\""))
        assertTrue(bytes.size > 1 shl 20)
    }

    @Test
    fun wrongPassphraseReadsNoChunkByte() {
        val file = T.resource("V1.fwx")
        val counter = T.CountingStream(ByteArrayInputStream(file))
        val code = T.openAll(file, "correct horse battery stapl".toCharArray(), counter = counter)
        assertEquals(FwxError.WRONG_PASSPHRASE, code)
        assertEquals(115L, counter.count)
    }

    @Test
    fun kdfParamsFailsBeforeAnyKdfWork() {
        // T7 with a passphrase that key derivation would refuse first: KDF_PARAMS proves it never started.
        val file = apply(T.resource("V1.fwx"), "set:51:000927bf")
        assertEquals(FwxError.KDF_PARAMS, T.openAll(file, unusable))
        assertEquals(FwxError.BAD_PASSPHRASE, T.openAll(T.resource("V1.fwx"), unusable))
    }

    @Test
    fun wrongAppNamesTheOtherApp() {
        try {
            FwxReader(ByteArrayInputStream(T.resource("V1.fwx")), T.PASSPHRASE.toCharArray(), "lumen", null)
        } catch (e: FwxException) {
            assertEquals(FwxError.WRONG_APP, e.code)
            assertEquals("tunnels", e.otherAppId)
            return
        }
        error("opened")
    }

    @Test
    fun laterCallsOnAFailedReaderAreIllegalState() {
        val file = T.resource("C3.fwx")
        val r = FwxReader(ByteArrayInputStream(file), T.PASSPHRASE.toCharArray(), "tunnels", null)
        r.next()!!.stream.readBytes()
        val first = try { r.next(); null } catch (e: FwxException) { e.code }
        assertEquals(FwxError.MALFORMED_PAYLOAD, first)
        try {
            r.next()
            error("allowed")
        } catch (e: IllegalStateException) {
            // spec section 8: misuse after a failure
        }

        val damaged = apply(T.resource("V1.fwx"), "flip:5000:0")
        val r2 = FwxReader(ByteArrayInputStream(damaged), T.PASSPHRASE.toCharArray(), "tunnels", null)
        r2.next()!!.stream.readBytes()
        r2.next()!!.stream.readBytes()
        val big = r2.next()!!.stream
        val code = try { big.readBytes(); null } catch (e: FwxException) { e.code }
        assertEquals(FwxError.DAMAGED, code)
        try {
            big.read(ByteArray(10))
            error("allowed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
