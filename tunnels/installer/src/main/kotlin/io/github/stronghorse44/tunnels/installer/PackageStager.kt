package io.github.stronghorse44.tunnels.installer

import android.content.Context
import android.os.Build
import io.github.stronghorse44.tunnels.archive.ArchiveError
import io.github.stronghorse44.tunnels.archive.ArchiveException
import io.github.stronghorse44.tunnels.archive.ArchiveFormat
import io.github.stronghorse44.tunnels.archive.Archives
import io.github.stronghorse44.tunnels.archive.DirectorySink
import io.github.stronghorse44.tunnels.install.Bundles
import io.github.stronghorse44.tunnels.install.PackageShape
import java.io.File
import java.io.IOException

/** APK files ready to write into an install session. [base] carries the app's identity. */
data class StagedPackage(val base: File, val apks: List<File>, val droppedSplits: List<String>)

object PackageStager {
    /** Turns a staged file (single APK or .apks/.xapk/.apkm bundle) into installable APKs. */
    fun prepare(context: Context, file: File): StagedPackage {
        if (ArchiveFormat.detect(file) != ArchiveFormat.ZIP) {
            throw IOException("This isn't an APK or app bundle.")
        }
        val entries = Archives.open(file, file.name).use { it.entries() }
        return when (Bundles.shape(entries.map { it.path })) {
            PackageShape.APK -> StagedPackage(file, listOf(file), emptyList())
            PackageShape.NOT_AN_APP -> throw IOException("This zip doesn't contain an app.")
            PackageShape.BUNDLE -> {
                val apkEntries = entries.filter { !it.isDirectory && it.path.endsWith(".apk", ignoreCase = true) }
                if (apkEntries.any { it.encrypted }) throw IOException("This bundle is encrypted and can't be installed.")
                val chosenPaths = Bundles.selectSplits(apkEntries.map { it.path }, Build.SUPPORTED_ABIS.toList()).toSet()
                val chosen = apkEntries.filter { it.path in chosenPaths }
                val outDir = File(file.parentFile, "splits").apply { mkdirs() }
                val sink = DirectorySink(outDir)
                try {
                    Archives.open(file, file.name).use { it.extract(chosen.map { e -> e.index }.toSet(), sink) }
                } catch (e: ArchiveException) {
                    throw IOException(describe(e.error), e)
                }
                val files = chosen.mapNotNull { e -> io.github.stronghorse44.tunnels.archive.SafePath.segments(e.path)?.let(sink::resolve) }
                    .filter { it.isFile }
                if (files.isEmpty()) throw IOException("No installable APKs found in this bundle.")
                StagedPackage(pickBase(files), files, apkEntries.map { it.path }.filterNot { it in chosenPaths })
            }
        }
    }

    private fun pickBase(files: List<File>): File =
        files.firstOrNull { it.name.equals("base.apk", true) }
            ?: files.firstOrNull { it.name.startsWith("base", true) }
            ?: files.firstOrNull { !it.name.contains("config.", true) && !it.name.startsWith("split_", true) }
            ?: files.maxBy { it.length() }

    private fun describe(error: ArchiveError) = when (error) {
        is ArchiveError.LimitExceeded -> "Bundle is too large: ${error.detail}"
        is ArchiveError.Corrupt -> "Bundle is damaged: ${error.detail}"
        else -> "Couldn't unpack the bundle."
    }
}
