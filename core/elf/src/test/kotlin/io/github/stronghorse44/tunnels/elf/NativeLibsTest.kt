package io.github.stronghorse44.tunnels.elf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLibsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good64 = ElfBuilder().build()
    private val weak32 = ElfBuilder(is64 = false, machine = ElfBuilder.EM_ARM, gnuStack = null, bindNow = ElfBuilder.BindNow.NONE).build()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(FileOutputStream(f)).use { z ->
            for ((path, data) in entries) {
                z.putNextEntry(ZipEntry(path)); z.write(data); z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun readsLibrariesAcrossSplitsAndPrefers64Bit() {
        val base = zip("base.apk", mapOf(
            "lib/armeabi-v7a/libfoo.so" to weak32,
            "lib/arm64-v8a/libfoo.so" to good64,
            "lib/arm64-v8a/libjunk.so" to ByteArray(300) { 1 },
            "lib/arm64-v8a/readme.txt" to ByteArray(3),
            "assets/lib/arm64-v8a/libhidden.so" to good64,
            "classes.dex" to ByteArray(10),
        ))
        val split = zip("split_config.x86_64.apk", mapOf(
            "lib/x86_64/libfoo.so" to good64,
            "lib/arm64-v8a/libfoo.so" to weak32, // duplicate path: first one wins
        ))
        val scan = NativeLibs.read(listOf(base, split))
        assertEquals(4, scan.total)
        assertEquals(setOf("armeabi-v7a", "arm64-v8a", "x86_64"), scan.abis)
        assertTrue(scan.has64Bit)
        assertFalse(scan.truncated)
        assertEquals(0, scan.skipped)
        assertEquals(0, scan.unreadable)
        assertEquals(listOf("arm64-v8a/libfoo.so", "arm64-v8a/libjunk.so", "x86_64/libfoo.so", "armeabi-v7a/libfoo.so"), scan.libs.map { it.path })
        assertEquals(3, scan.parsed.size)
        val junk = scan.libs.single { it.name == "libjunk.so" }
        assertNotNull(junk.report.parseError)
        val arm64 = scan.libs.first { it.path == "arm64-v8a/libfoo.so" }
        assertNull(arm64.report.parseError)
        assertEquals("arm64", arm64.report.arch)
        assertTrue(arm64.report.nx)
        val arm32 = scan.libs.first { it.path == "armeabi-v7a/libfoo.so" }
        assertEquals("arm", arm32.report.arch)
        assertFalse(arm32.report.nx)
        assertFalse(arm32.report.bindNow)
    }

    @Test
    fun capsCountAndSize() {
        val apk = zip("many.apk", mapOf(
            "lib/arm64-v8a/liba.so" to good64,
            "lib/arm64-v8a/libb.so" to good64,
            "lib/arm64-v8a/libc.so" to good64,
            "lib/armeabi-v7a/liba.so" to weak32,
        ))
        val capped = NativeLibs.read(listOf(apk), maxLibs = 2)
        assertEquals(4, capped.total)
        assertTrue(capped.truncated)
        assertEquals(listOf("arm64-v8a/liba.so", "arm64-v8a/libb.so"), capped.libs.map { it.path })

        val perLib = NativeLibs.read(listOf(apk), maxLibBytes = good64.size - 1L)
        assertEquals(3, perLib.skipped)
        assertEquals(listOf("armeabi-v7a/liba.so"), perLib.libs.map { it.path })

        val total = NativeLibs.read(listOf(apk), maxTotalBytes = good64.size * 2L)
        assertEquals(2, total.skipped)
        assertEquals(2, total.libs.size)
        assertFalse(total.truncated)
    }

    @Test
    fun only32BitCode() {
        val apk = zip("old.apk", mapOf("lib/armeabi-v7a/libold.so" to weak32, "lib/x86/libold.so" to weak32))
        val scan = NativeLibs.read(listOf(apk))
        assertFalse(scan.has64Bit)
        assertEquals(setOf("armeabi-v7a", "x86"), scan.abis)
    }

    @Test
    fun survivesFilesThatAreNotZips() {
        val junk = tmp.newFile("junk.apk").apply { writeBytes(ByteArray(64) { 7 }) }
        val missing = File(tmp.root, "missing.apk")
        val scan = NativeLibs.read(listOf(junk, missing))
        assertEquals(NativeLibScan.EMPTY.copy(unreadable = 2), scan)
        assertEquals(NativeLibScan.EMPTY, NativeLibs.read(emptyList()))
        val noNative = zip("plain.apk", mapOf("classes.dex" to ByteArray(4)))
        assertEquals(NativeLibScan.EMPTY, NativeLibs.read(listOf(noNative)))
    }
}
