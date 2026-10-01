package io.github.stronghorse44.tunnels.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file copied into app-private cache so it can be read with random access and survives the grant. */
data class StagedFile(val file: File, val displayName: String)

/** Private scratch area for incoming files. Cleared on app start and when screens finish. */
object Staging {
    private const val DIR = "staging"
    private const val STALE_MS = 6 * 60 * 60 * 1000L

    fun root(context: Context) = File(context.cacheDir, DIR)

    fun newDir(context: Context): File =
        File(root(context), UUID.randomUUID().toString()).apply { mkdirs() }

    /** True when [file] lives inside the staging area (guards paths passed between our screens). */
    fun contains(context: Context, file: File): Boolean =
        file.canonicalPath.startsWith(root(context).canonicalPath + File.separator)

    fun copyIn(context: Context, uri: Uri): StagedFile {
        val name = displayName(context, uri)
        val dir = newDir(context)
        val target = File(dir, name.replace('/', '_').ifBlank { "file" })
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
        input.use { src -> target.outputStream().use { src.copyTo(it, 64 * 1024) } }
        return StagedFile(target, name)
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun discard(context: Context, file: File?) {
        if (file == null || !contains(context, file)) return
        val dir = generateSequence(file) { it.parentFile }.firstOrNull { it.parentFile == root(context) } ?: return
        dir.deleteRecursively()
    }

    fun clearStale(context: Context, now: Long = System.currentTimeMillis()) {
        root(context).listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.deleteRecursively() }
    }

    /** The content URI an incoming VIEW or SEND intent points at. */
    fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        null -> null
        else -> intent.data
    }
}
