package io.github.stronghorse44.tunnels.breaches

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Serves the fetched breach list to Linx from memory (specs/B11-linx.md section 10.4): no file exists anywhere.
 * Not exported; Linx reaches it only through the one-address grant in the send intent. It serves exactly
 * `content://<authority>/catalogue/<token>` for the token of the file [BreachHolder.shared] holds, at most
 * [BreachHolder.MAX_SERVES] times and for ten minutes; any other address, mode or token is a
 * [FileNotFoundException]. The bytes go out through a reliable pipe, so a reader that stops early is noticed.
 */
class BreachShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun tokenOf(uri: Uri): String? {
        val context = context ?: return null
        return ShareUri.tokenOf(
            expectedAuthority = BreachShare.authority(context),
            scheme = uri.scheme,
            authority = uri.authority,
            pathSegments = uri.pathSegments,
            hasQueryOrFragment = uri.encodedQuery != null || uri.encodedFragment != null,
        )
    }

    override fun getType(uri: Uri): String? = if (tokenOf(uri) != null) Catalogue.MIME_TYPE else null

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val info = tokenOf(uri)?.let { BreachHolder.shared.describe(it) } ?: return null
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            .toTypedArray()
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) info.displayName else info.size.toLong() })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only.")
        val token = tokenOf(uri) ?: throw FileNotFoundException("No such address.")
        val bytes = BreachHolder.shared.take(token) ?: throw FileNotFoundException("The list is no longer held: send it again from Tunnels.")
        val pipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (e: IOException) {
            bytes.fill(0)
            throw FileNotFoundException("Could not open a pipe.")
        }
        val write = pipe[1]
        // The write is bounded: a reader that never reads cannot keep the bytes alive past PipeWriter.TIMEOUT_MS.
        PipeWriter.start(bytes, ParcelFileDescriptor.AutoCloseOutputStream(write), abort = { write.closeWithError("The reader did not read in time.") })
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only.")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only.")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only.")
}
