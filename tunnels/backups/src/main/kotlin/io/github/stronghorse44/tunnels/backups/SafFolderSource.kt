package io.github.stronghorse44.tunnels.backups

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream

/**
 * The export folder through the Storage Access Framework: a listing of names, sizes and dates, and one file opened at
 * a time. The only grant is the read-only tree access the user gave in the system folder picker.
 */
internal class SafFolderSource(private val resolver: ContentResolver, private val tree: Uri) : FolderSource {
    override fun list(dir: FolderEntry?): List<FolderEntry> {
        val parent = dir?.id ?: DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("the folder gave no listing")
        return cursor.use { c ->
            val out = ArrayList<FolderEntry>(c.count)
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += FolderEntry(
                    id = id,
                    name = c.getString(1) ?: "",
                    isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    modifiedMs = if (c.isNull(3)) 0L else c.getLong(3),
                    length = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
            out
        }
    }

    override fun open(file: FolderEntry): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, file.id)) ?: throw IOException("cannot open the file")

    companion object {
        /** A source for the stored tree URI, or null when the read grant is gone (revoked, or the app was reinstalled). */
        fun open(context: Context, treeUri: String): SafFolderSource? {
            val uri = Uri.parse(treeUri)
            val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            return if (granted) SafFolderSource(context.contentResolver, uri) else null
        }
    }
}
