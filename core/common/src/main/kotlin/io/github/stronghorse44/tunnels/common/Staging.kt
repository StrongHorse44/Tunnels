package io.github.stronghorse44.tunnels.common

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import io.github.stronghorse44.tunnels.engine.IncomingUris
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file copied into app-private cache so it can be read with random access and survives the grant. */
data class StagedFile(val file: File, val displayName: String)

/** [Staging.copyIn] refused a file larger than [Staging.MAX_COPY_BYTES]; nothing of it is kept. */
class StagingTooLargeException(val limit: Long) :
    IOException("This file is larger than ${limit shr 30} GB, the most Tunnels copies in.")

/**
 * Private scratch area for incoming files (plaintext copies of the owner's files). All of it is cleared on every app
 * start and when screens finish (audit L9).
 */
object Staging {
    private const val DIR = "staging"
    private const val TRASH = "staging-trash-"

    /** The largest file [copyIn] copies (audit L2): 2 GiB. */
    const val MAX_COPY_BYTES = 2L shl 30

    fun root(context: Context) = File(context.cacheDir, DIR)

    fun newDir(context: Context): File =
        File(root(context), UUID.randomUUID().toString()).apply { mkdirs() }

    /** True when [file] lives inside the staging area (guards paths passed between our screens). */
    fun contains(context: Context, file: File): Boolean =
        file.canonicalPath.startsWith(root(context).canonicalPath + File.separator)

    /**
     * Copies [uri] in. Only addresses [incomingUri] would accept; at most [MAX_COPY_BYTES], else
     * [StagingTooLargeException] and the partial copy is deleted.
     */
    fun copyIn(context: Context, uri: Uri): StagedFile {
        if (!accepts(context, uri)) throw IOException("Tunnels opens only files shared by another app.")
        val name = displayName(context, uri)
        val dir = newDir(context)
        val target = File(dir, name.replace('/', '_').ifBlank { "file" })
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
            input.use { src -> target.outputStream().use { out -> copyCapped(src, out, MAX_COPY_BYTES) } }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        return StagedFile(target, name)
    }

    private fun copyCapped(input: java.io.InputStream, out: java.io.OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            total += n
            if (total > limit) throw StagingTooLargeException(limit)
            out.write(buf, 0, n)
        }
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

    /**
     * Empties the staging area at app start, before any screen copies a file in (audit L9): the folder is renamed away
     * at once, so a file staged a moment later is never caught, and the renamed folders are deleted by [clearTrash].
     */
    fun clearOnStart(context: Context) {
        val root = root(context)
        if (root.exists() && !root.renameTo(File(context.cacheDir, TRASH + UUID.randomUUID()))) root.deleteRecursively()
    }

    /** Deletes what [clearOnStart] set aside. Slow for big files, so off the main thread. */
    fun clearTrash(context: Context) {
        context.cacheDir.listFiles()?.filter { it.name.startsWith(TRASH) }?.forEach { it.deleteRecursively() }
    }

    /**
     * The content URI an incoming VIEW or SEND intent points at, or null. Only a `content://` address that is not one
     * of Tunnels' own providers (audit L1): an explicit intent skips the manifest's scheme filter.
     */
    fun incomingUri(context: Context, intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            null -> null
            else -> intent.data
        } ?: return null
        return uri.takeIf { accepts(context, it) }
    }

    /** [IncomingUris.accepts], and the provider serving it is not this app (whatever its authority is called). */
    private fun accepts(context: Context, uri: Uri): Boolean {
        if (!IncomingUris.accepts(uri.scheme, uri.authority, context.packageName)) return false
        val owner = runCatching { context.packageManager.resolveContentProvider(uri.authority!!, PackageManager.ComponentInfoFlags.of(0)) }
            .getOrNull()?.packageName
        return owner != context.packageName
    }
}
