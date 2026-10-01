package io.github.stronghorse44.tunnels.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkContentsTest {
    @get:Rule val tmp = TemporaryFolder()

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
    fun countsDexAndNativeLibsAcrossSplits() {
        val base = zip("base.apk", mapOf(
            "classes.dex" to ByteArray(300) { 1 },           // not a dex
            "classes2.dex" to ByteArray(0x80),               // zero header: rejected, no exception
            "lib/arm64-v8a/libfoo.so" to ByteArray(10),
            "lib/armeabi-v7a/libfoo.so" to ByteArray(10),
            "lib/arm64-v8a/notalib.txt" to ByteArray(1),
            "assets/classes.dex" to ByteArray(5),            // wrong place, ignored
            "res/raw/thing" to ByteArray(2),
        ))
        val split = zip("split_config.x86_64.apk", mapOf(
            "lib/x86_64/libfoo.so" to ByteArray(10),
            "classes3.dex" to ByteArray(0x80),
        ))
        val c = ApkContents.read(listOf(base, split))
        assertEquals(3, c.dexFiles)
        assertEquals(0, c.dexRead)
        assertEquals(3, c.dexInvalid)
        assertEquals(ApkContents.SKIPPED_UNREADABLE, c.dexSkipped)   // nothing parsed: say so
        assertEquals(setOf("arm64-v8a", "armeabi-v7a", "x86_64"), c.abis)
        assertEquals(3, c.nativeLibs)
        assertEquals(base.length() + split.length(), c.bytes)
        assertEquals(emptyMap<String, Int>(), c.hits)
    }

    @Test
    fun capsLargeDexInsteadOfReadingIt() {
        val apk = zip("big.apk", mapOf("classes.dex" to ByteArray(2000), "classes2.dex" to ByteArray(100)))
        val perFile = ApkContents.read(listOf(apk), maxDexBytes = 1000)
        assertEquals(ApkContents.SKIPPED_TOO_LARGE, perFile.dexSkipped)
        assertEquals(2, perFile.dexFiles)
        assertEquals(1, perFile.dexInvalid)           // only the small one was looked at
        val total = ApkContents.read(listOf(apk), maxDexBytes = 5000, maxTotalDexBytes = 2050)
        assertEquals(ApkContents.SKIPPED_TOO_MANY, total.dexSkipped)
    }

    @Test
    fun survivesFilesThatAreNotZips() {
        val junk = tmp.newFile("junk.apk").apply { writeBytes(ByteArray(64) { 7 }) }
        val missing = File(tmp.root, "missing.apk")
        val c = ApkContents.read(listOf(junk, missing))
        assertEquals(2, c.dexInvalid)
        assertEquals(0, c.dexFiles)
        assertEquals(ApkContents.EMPTY.copy(dexInvalid = 2, dexSkipped = ApkContents.SKIPPED_UNREADABLE, bytes = 64), c)
        assertEquals(ApkContents.EMPTY.copy(dexSkipped = ApkContents.SKIPPED_UNREADABLE), ApkContents.read(emptyList()))
    }

    @Test
    fun apkWithoutDexIsNotFlaggedUnreadable() {
        val apk = zip("nocode.apk", mapOf("lib/arm64-v8a/libfoo.so" to ByteArray(10), "res/raw/thing" to ByteArray(2)))
        val c = ApkContents.read(listOf(apk))
        assertEquals(0, c.dexFiles)
        assertNull(c.dexSkipped)
        assertEquals(1, c.nativeLibs)
    }
}
