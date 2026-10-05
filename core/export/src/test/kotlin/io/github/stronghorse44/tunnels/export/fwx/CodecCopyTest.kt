package io.github.stronghorse44.tunnels.export.fwx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The codec in export/fwx is a copy of the fieldwork program's canonical FWX codec, unchanged apart from its
 * package line and one provenance line (fieldwork codec/README.md). Each file's first line names the canonical
 * SHA-256 of the file without its package line; an edit made here, rather than upstream, fails this test.
 */
class CodecCopyTest {

    private val header = Regex("""^// FWX codec (\S+), canonical sha256 ([0-9a-f]{64}) \(fieldwork codec/kotlin/src/main/kotlin/fwx/(\w+\.kt)(?: at [0-9a-f]{7,40})?\)$""")

    /** fieldwork codec/kotlin/SOURCES.sha256 for codec 1.2.0. */
    private val canonical = mapOf(
        "Fwx.kt" to "e5a99cd6a7230d6a079da75bc72844a7712f7387f95f5e1ddb1e7f8c912505d8",
        "FwxError.kt" to "1bb7d381136f8f243d5909651d3a31c5a736731833e19d8047bc04b1d257ed72",
        "FwxKdf.kt" to "cd7e30e7488d2187db8d36dd1e5617d4519c4f232ee4479c2f19bd486930fccb",
        "FwxReader.kt" to "36fe11e3c8b67c3abe3271cc51ca2902db797e3ef006b5131e72d906cab5dad5",
        "FwxWriter.kt" to "ad0062560e548d373d2460a7fe51aaf215ddb7b1075410a9fb5b4973af1abfc5",
    )

    private val relative = "src/main/kotlin/io/github/stronghorse44/tunnels/export/fwx"

    private fun codecDir(): File =
        listOf(File(relative), File("core/export/$relative")).firstOrNull { it.isDirectory }
            ?: error("codec sources not found from ${File("").absolutePath}")

    @Test
    fun everyCodecFileMatchesItsCanonicalHash() {
        val files = codecDir().listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }
        assertEquals(canonical.keys.sorted(), files.map { it.name })
        for (file in files) {
            val lines = file.readText().split('\n')
            val match = header.matchEntire(lines.first()) ?: error("${file.name}: missing the provenance line")
            val (version, hash, name) = match.destructured
            assertEquals(file.name, name)
            assertEquals(Fwx.CODEC_VERSION, version)
            assertEquals(canonical[file.name], hash)
            assertEquals("package io.github.stronghorse44.tunnels.export.fwx", lines[1])
            val body = lines.drop(1).filterNot { it.startsWith("package ") }.joinToString("\n")
            assertEquals("${file.name} differs from the canonical codec", hash, sha256(body.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun theCodecIsVersion120() {
        assertEquals("1.2.0", Fwx.CODEC_VERSION)
    }

    /**
     * The codec README's "no test hooks in production code" check: nothing outside src/test may fix the salt or
     * nonce prefix or reach the writer's private constructor. The codec's own FwxWriter.kt holds the private Seeds
     * class and is excluded (its bytes are pinned above).
     */
    @Test
    fun noTestHooksOutsideTestSources() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile
        assertTrue("repository root not found", root != null)
        val hooks = Regex("""fixedWriter|withFixedRandom|pbkdf2Runs|getDeclaredConstructor|Seeds\(|isAccessible""")
        val offenders = root!!.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" }
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f -> !f.invariantSeparatorsPath.contains("/src/test/") && !f.invariantSeparatorsPath.contains("/src/androidTest/") }
            .filter { f -> f.invariantSeparatorsPath.substringAfter(root.invariantSeparatorsPath) != "/core/export/$relative/FwxWriter.kt" }
            .filter { hooks.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertEquals("test hooks in production sources", emptyList<String>(), offenders)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
