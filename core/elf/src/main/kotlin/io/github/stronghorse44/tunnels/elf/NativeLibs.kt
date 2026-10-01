package io.github.stronghorse44.tunnels.elf

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/** One native library inside an app's APKs and what the ELF parser said about it. */
data class NativeLib(val abi: String, val name: String, val report: HardeningReport) {
    /** `<abi>/<file>`, unique within an app. */
    val path: String get() = "$abi/$name"
}

/** The native code of one app, summarised across its base and split APKs. */
data class NativeLibScan(
    /** Every lib/<abi>/<file>.so entry seen, parsed or not. */
    val total: Int,
    /** lib/<abi> directories that hold .so files. */
    val abis: Set<String>,
    /** Libraries read and parsed, up to the cap; failed parses carry a parseError. */
    val libs: List<NativeLib>,
    /** Libraries over the size caps, not read. */
    val skipped: Int,
    /** More libraries than the cap: the rest were not read. */
    val truncated: Boolean,
    /** APK files or entries that could not be read at all. */
    val unreadable: Int,
) {
    val parsed: List<NativeLib> get() = libs.filter { it.report.parseError == null }

    val has64Bit: Boolean get() = abis.any { it in NativeLibs.ABIS_64 }

    companion object {
        val EMPTY = NativeLibScan(0, emptySet(), emptyList(), 0, false, 0)
    }
}

/** Reads native libraries out of APK files. Plain Java zip access, so it is unit-tested without a device. */
object NativeLibs {
    /** Libraries beyond this many per app are counted but not parsed. */
    const val MAX_LIBS = 40
    /** One library above this is skipped rather than read into memory. */
    const val MAX_LIB_BYTES = 48L * 1024 * 1024
    /** Per app, across all parsed libraries, so one giant app cannot stall a scan. */
    const val MAX_TOTAL_LIB_BYTES = 256L * 1024 * 1024
    /** A single library may take at most this share of the Java heap, whatever [MAX_LIB_BYTES] says. */
    const val HEAP_SHARE = 4L

    val ABIS_64 = setOf("arm64-v8a", "x86_64", "riscv64")

    private val LIB_NAME = Regex("lib/([^/]+)/([^/]+\\.so)")

    private class Entry(val abi: String, val name: String, val size: Long, val zip: ZipFile, val entry: java.util.zip.ZipEntry)

    /** 64-bit ABIs first so the cap keeps the code that actually runs on a 64-bit phone. */
    private val ORDER = compareBy<Entry>({ it.abi !in ABIS_64 }, { it.abi }, { it.name })

    fun read(
        apks: List<File>,
        maxLibs: Int = MAX_LIBS,
        maxLibBytes: Long = MAX_LIB_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_LIB_BYTES,
    ): NativeLibScan {
        var unreadable = 0
        // On a small or fragmented heap even an in-cap library may not fit; such libraries count as skipped.
        val libCap = minOf(maxLibBytes, Runtime.getRuntime().maxMemory() / HEAP_SHARE)
        val zips = ArrayList<ZipFile>()
        try {
            val entries = ArrayList<Entry>()
            val seen = HashSet<String>()
            for (apk in apks) {
                val zip = try { ZipFile(apk) } catch (_: Exception) { unreadable++; continue }
                zips += zip
                val it = zip.entries()
                while (it.hasMoreElements()) {
                    val e = it.nextElement()
                    val m = LIB_NAME.matchEntire(e.name) ?: continue
                    val abi = m.groupValues[1]
                    val name = m.groupValues[2]
                    if (seen.add("$abi/$name")) entries += Entry(abi, name, e.size, zip, e)
                }
            }
            entries.sortWith(ORDER)
            val abis = entries.mapTo(HashSet()) { it.abi }
            val libs = ArrayList<NativeLib>(minOf(entries.size, maxLibs))
            var skipped = 0
            var bytes = 0L
            var considered = 0
            for (entry in entries) {
                if (considered >= maxLibs) break
                considered++
                if (entry.size < 0 || entry.size > libCap || bytes + entry.size > maxTotalBytes) {
                    skipped++
                    continue
                }
                // OutOfMemoryError is an Error, so nothing upstream would catch it: treat it as "too big".
                val buf = try {
                    ByteArray(entry.size.toInt())
                } catch (_: OutOfMemoryError) {
                    skipped++
                    continue
                }
                val ok = try {
                    entry.zip.getInputStream(entry.entry).use { DataInputStream(it).readFully(buf) }
                    true
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    unreadable++
                    continue
                }
                bytes += entry.size
                libs += NativeLib(entry.abi, entry.name, ElfParser.parse(buf))
            }
            return NativeLibScan(entries.size, abis, libs, skipped, entries.size > maxLibs, unreadable)
        } finally {
            zips.forEach { z -> runCatching { z.close() } }
        }
    }
}
