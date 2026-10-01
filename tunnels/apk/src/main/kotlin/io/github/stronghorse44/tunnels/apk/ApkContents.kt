package io.github.stronghorse44.tunnels.apk

import io.github.stronghorse44.tunnels.trackers.DexTypeReader
import io.github.stronghorse44.tunnels.trackers.TrackerMatcher
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * What the APK files of one app contain, summarised: tracker hits from every readable classes*.dex,
 * and the native library directories. Plain Java, so it is unit-tested without a device.
 */
data class ApkContents(
    /** classes*.dex entries seen across base and splits. */
    val dexFiles: Int,
    /** Of those, parsed successfully. */
    val dexRead: Int,
    /** Entries that were not valid dex or could not be read. */
    val dexInvalid: Int,
    /** Why some dex was not read (size caps), or null when everything was. */
    val dexSkipped: String?,
    /** Tracker id to matching class count, merged over all dex files. */
    val hits: Map<String, Int>,
    /** lib/<abi> directories that hold .so files. */
    val abis: Set<String>,
    val nativeLibs: Int,
    /** Total size of the APK files on disk. */
    val bytes: Long,
) {
    companion object {
        /** One dex above this is skipped; a 48 MB dex already holds hundreds of thousands of classes. */
        const val MAX_DEX_BYTES = 48L * 1024 * 1024
        /** Per app, across all its dex files, so one huge app cannot stall a scan. */
        const val MAX_TOTAL_DEX_BYTES = 256L * 1024 * 1024
        const val SKIPPED_TOO_LARGE = "dex too large"
        const val SKIPPED_TOO_MANY = "too many dex bytes"

        private val DEX_NAME = Regex("classes\\d*\\.dex")
        private val LIB_NAME = Regex("lib/([^/]+)/[^/]+\\.so")

        val EMPTY = ApkContents(0, 0, 0, null, emptyMap(), emptySet(), 0, 0)

        fun read(
            apks: List<File>,
            matcher: TrackerMatcher = TrackerMatcher.DEFAULT,
            maxDexBytes: Long = MAX_DEX_BYTES,
            maxTotalDexBytes: Long = MAX_TOTAL_DEX_BYTES,
        ): ApkContents {
            var dexFiles = 0
            var dexRead = 0
            var dexInvalid = 0
            var skipped: String? = null
            var dexBytes = 0L
            var libs = 0
            val abis = HashSet<String>()
            val hits = HashMap<String, Int>()
            var bytes = 0L
            for (apk in apks) {
                bytes += apk.length()
                val zip = try { ZipFile(apk) } catch (_: Exception) { dexInvalid++; continue }
                zip.use {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (DEX_NAME.matches(name)) {
                            dexFiles++
                            val size = entry.size
                            when {
                                size < 0 || size > maxDexBytes -> { skipped = skipped ?: SKIPPED_TOO_LARGE }
                                dexBytes + size > maxTotalDexBytes -> { skipped = skipped ?: SKIPPED_TOO_MANY }
                                else -> {
                                    dexBytes += size
                                    val buf = ByteArray(size.toInt())
                                    val ok = try {
                                        zip.getInputStream(entry).use { DataInputStream(it).readFully(buf) }
                                        true
                                    } catch (_: Exception) { false }
                                    val scan = if (ok) DexTypeReader.scan(buf, matcher) else null
                                    if (scan != null && scan.valid) {
                                        dexRead++
                                        for ((id, n) in scan.hits) hits[id] = (hits[id] ?: 0) + n
                                    } else dexInvalid++
                                }
                            }
                        } else {
                            LIB_NAME.matchEntire(name)?.let { m -> abis += m.groupValues[1]; libs++ }
                        }
                    }
                }
            }
            return ApkContents(dexFiles, dexRead, dexInvalid, skipped, hits, abis, libs, bytes)
        }
    }
}
