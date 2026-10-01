package io.github.stronghorse44.tunnels.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun extractAll(archive: File, password: String? = null, limits: ExtractLimits = ExtractLimits()): Pair<File, ExtractResult> {
        val out = tmp.newFolder()
        val result = Archives.open(archive, archive.name, password?.toCharArray()).use {
            it.extract(null, DirectorySink(out), limits)
        }
        return out to result
    }

    @Test
    fun safePathBlocksTraversal() {
        assertEquals(null, SafePath.segments("../etc/passwd"))
        assertEquals(null, SafePath.segments("a/../../b"))
        assertEquals(null, SafePath.segments("C:/Windows/x"))
        assertEquals(listOf("etc", "passwd"), SafePath.segments("/etc/passwd"))
        assertEquals(listOf("a", "b.txt"), SafePath.segments("a\\.\\b.txt"))
        assertEquals(listOf("we_ird_.txt"), SafePath.segments("we:ird?.txt"))
    }

    @Test
    fun plainZipWithSlipEntry() {
        val zip = tmp.newFile("plain.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("dir/")); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/hello.txt")); z.write("hello".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect(zip))
        val (out, result) = extractAll(zip)
        assertEquals("hello", File(out, "dir/hello.txt").readText())
        assertEquals(1, result.files)
        assertEquals(listOf("../evil.txt"), result.skipped.map { it.path })
        assertFalse(File(out.parentFile, "evil.txt").exists())
    }

    @Test
    fun encryptedZipNeedsPassword() {
        val src = tmp.newFile("secret.txt").apply { writeText("top secret") }
        val zipFile = File(tmp.root, "enc.zip")
        ZipFile(zipFile, "hunter2".toCharArray()).use {
            it.addFile(src, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            })
        }
        Archives.open(zipFile, "enc.zip").use { r ->
            assertTrue(r.entries().single().encrypted)
            expectError<ArchiveError.PasswordRequired> { r.extract(null, DirectorySink(tmp.newFolder())) }
        }
        expectError<ArchiveError.WrongPassword> { extractAll(zipFile, "wrong") }
        val (out, _) = extractAll(zipFile, "hunter2")
        assertEquals("top secret", File(out, "secret.txt").readText())
    }

    @Test
    fun sevenZRoundTripAndSelection() {
        val f = tmp.newFile("a.7z")
        SevenZOutputFile(f).use { o ->
            for (name in listOf("one.txt", "sub/two.txt")) {
                val src = tmp.newFile(name.replace('/', '_')).apply { writeText(name) }
                o.putArchiveEntry(o.createArchiveEntry(src, name)); o.write(src.readBytes()); o.closeArchiveEntry()
            }
        }
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.detect(f))
        Archives.open(f, f.name).use { r ->
            assertEquals(listOf("one.txt", "sub/two.txt"), r.entries().map { it.path })
            val out = tmp.newFolder()
            val res = r.extract(setOf(1), DirectorySink(out))
            assertEquals(1, res.files)
            assertEquals("sub/two.txt", File(out, "sub/two.txt").readText())
            assertFalse(File(out, "one.txt").exists())
        }
    }

    @Test
    fun tarGzAndTarXzSkipSymlinks() {
        for ((name, wrap) in listOf<Pair<String, (java.io.OutputStream) -> java.io.OutputStream>>(
            "a.tar.gz" to { GzipCompressorOutputStream(it) },
            "a.tar.xz" to { XZCompressorOutputStream(it) },
        )) {
            val f = tmp.newFile(name)
            TarArchiveOutputStream(wrap(FileOutputStream(f))).use { t ->
                val data = "tar data".toByteArray()
                t.putArchiveEntry(TarArchiveEntry("x/file.txt").apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry()
                t.putArchiveEntry(TarArchiveEntry("x/link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); t.closeArchiveEntry()
            }
            val (out, res) = extractAll(f)
            assertEquals("tar data", File(out, "x/file.txt").readText())
            assertEquals(1, res.files)
            assertEquals(1, res.skipped.size)
        }
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect(File(tmp.root, "a.tar.gz")))
        assertEquals(ArchiveFormat.TAR_XZ, ArchiveFormat.detect(File(tmp.root, "a.tar.xz")))
    }

    @Test
    fun singleGzip() {
        val f = tmp.newFile("notes.txt.gz")
        GzipCompressorOutputStream(FileOutputStream(f)).use { it.write("plain".toByteArray()) }
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.detect(f))
        val (out, _) = extractAll(f)
        assertEquals("plain", File(out, "notes.txt").readText())
    }

    @Test
    fun bombIsStopped() {
        val zip = tmp.newFile("bomb.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("zeros.bin"))
            val chunk = ByteArray(1 shl 20)
            repeat(20) { z.write(chunk) }
            z.closeEntry()
        }
        expectError<ArchiveError.LimitExceeded> {
            extractAll(zip, limits = ExtractLimits(maxRatio = 50, ratioFloorBytes = 1 shl 20))
        }
        expectError<ArchiveError.LimitExceeded> {
            extractAll(zip, limits = ExtractLimits(maxTotalBytes = 5L shl 20))
        }
    }

    @Test
    fun unknownAndRarRejected() {
        val junk = tmp.newFile("junk.bin").apply { writeText("not an archive at all") }
        expectError<ArchiveError.Unsupported> { Archives.open(junk, junk.name) }
        val rar = tmp.newFile("x.rar").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)) }
        expectError<ArchiveError.Unsupported> { Archives.open(rar, rar.name) }
    }

    private inline fun <reified T : ArchiveError> expectError(block: () -> Unit) {
        try {
            block()
            fail("expected ${T::class.simpleName}")
        } catch (e: ArchiveException) {
            assertTrue("got ${e.error}", e.error is T)
        }
    }
}
