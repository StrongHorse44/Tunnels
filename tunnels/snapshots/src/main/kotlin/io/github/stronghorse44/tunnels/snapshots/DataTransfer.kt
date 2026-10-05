package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.stronghorse44.tunnels.export.ExportCleanup
import io.github.stronghorse44.tunnels.export.ExportFailure
import io.github.stronghorse44.tunnels.export.ExportResult
import io.github.stronghorse44.tunnels.export.ImportSummary
import io.github.stronghorse44.tunnels.export.Inspected
import io.github.stronghorse44.tunnels.export.LegacyImport
import io.github.stronghorse44.tunnels.export.TunnelsBundle
import io.github.stronghorse44.tunnels.export.TunnelsExport
import io.github.stronghorse44.tunnels.store.TunnelsDao
import io.github.stronghorse44.tunnels.watch.WatchScheduler
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Export and import of the Tunnels bundle through documents the system file picker gave (no permission needed). The
 * logic is in core:export; this is the Android part: the picked document's streams, deleting a failed export, moving an
 * older build's plaintext confirmed-networks file into the store, and the store. Every function blocks or suspends: call
 * them off the main thread. The passphrase is the caller's CharArray; it is never stored, logged or put in saved state,
 * and the caller zeroes it.
 */
class DataTransfer(
    context: Context,
    private val dao: TunnelsDao,
    private val networks: ConfirmedNetworks = ConfirmedNetworks(context, dao),
    /** Applied after an import that carried background-check settings, so the job follows them at once (tests pass a no-op). */
    private val applyWatchSettings: (WatchSettings) -> Unit = { WatchScheduler.apply(context.applicationContext, it) },
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** An export that failed after [cleanup]: what became of the document the user picked. */
    class ExportFailed(val cleanup: ExportCleanup, cause: Throwable) : Exception(cause.message, cause)

    /** Writes everything into [uri], reads the file back through the import's reader, and returns what it holds and its size. */
    suspend fun export(uri: Uri, passphrase: CharArray, createdMs: Long): ExportResult {
        var opened = false
        try {
            val data = StoreBundles.gather(dao, networks.all())
            return TunnelsExport.run(
                data, passphrase, createdMs, appVersion(),
                open = {
                    (resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("the file couldn't be opened")).also { opened = true }
                },
                reopen = { resolver.openInputStream(uri) ?: throw FileNotFoundException("the file couldn't be read back") },
            )
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            // A document the export opened (and so truncated) or the picker's new empty one is deleted; an existing file
            // that was picked but never opened for writing is left as it was.
            val cleanup = when {
                opened || size(uri) == 0L -> if (delete(uri)) ExportCleanup.DELETED else ExportCleanup.NOT_DELETED
                else -> ExportCleanup.UNTOUCHED
            }
            throw ExportFailed(cleanup, (e as? ExportFailure)?.cause ?: e)
        }
    }

    /**
     * Deletes the picker's document for an export that never started, but only while it is empty: one that already
     * holds something (an earlier export picked to be overwritten) is left as it is.
     */
    fun discardIfEmpty(uri: Uri): Boolean = size(uri) == 0L && delete(uri)

    /** The container spec's steps 1 to 6, no passphrase: reads at most 202 bytes. Throws FwxException for a file this app will not open. */
    fun inspect(uri: Uri): Inspected =
        (resolver.openInputStream(uri) ?: throw FileNotFoundException("the file couldn't be opened")).use {
            TunnelsBundle.inspect(it, size(uri))
        }

    /**
     * Reads [uri] completely (an FWX bundle or an old TSNAPE1 export, told apart by its first bytes), verifies it and
     * stages it in memory; only then does one transaction write it ([StoreBundles.commit]). Any failure before that
     * leaves the phone as it was: FwxException for the file, ImportCommitFailed for the write.
     */
    suspend fun import(uri: Uri, passphrase: CharArray): ImportSummary {
        val data = when (inspect(uri)) {
            Inspected.Legacy -> LegacyImport.read(readLegacy(uri), passphrase)
            is Inspected.Fwx -> (resolver.openInputStream(uri) ?: throw FileNotFoundException("the file couldn't be opened")).use {
                TunnelsBundle.read(BufferedInputStream(it, 64 * 1024), passphrase)
            }
        }
        val summary = StoreBundles.commit(dao, networks, data)
        // The settings are committed; a failure to re-arm the job must not read as a failed import (the next start re-arms it).
        data.settings[WatchSettings.KEY]?.let { value -> runCatching { applyWatchSettings(WatchSettings.decode(value)) } }
        return summary
    }

    /** The whole old-format file, at most [LegacyImport.MAX_FILE_BYTES]: its single-shot cipher needs it all in memory. */
    private fun readLegacy(uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("the file couldn't be opened")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > LegacyImport.MAX_FILE_BYTES) throw java.io.IOException("the file is larger than an old Tunnels export can be")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        false
    }

    fun size(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun appVersion(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0)).versionName.orEmpty()
}
