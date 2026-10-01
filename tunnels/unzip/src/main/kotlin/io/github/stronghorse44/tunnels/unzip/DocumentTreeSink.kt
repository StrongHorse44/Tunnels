package io.github.stronghorse44.tunnels.unzip

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import io.github.stronghorse44.tunnels.archive.ExtractSink
import java.io.IOException
import java.io.OutputStream

/** Writes extracted entries into a folder the user picked through the system folder picker. */
class DocumentTreeSink(private val resolver: ContentResolver, root: DocumentFile) : ExtractSink {
    private val dirs = HashMap<List<String>, DocumentFile>().apply { put(emptyList(), root) }

    override fun directory(segments: List<String>) {
        dirFor(segments)
    }

    override fun file(segments: List<String>): OutputStream {
        val parent = dirFor(segments.dropLast(1))
        val name = segments.last()
        val doc = parent.createFile("application/octet-stream", name) ?: throw IOException("Couldn't create $name")
        return resolver.openOutputStream(doc.uri, "w") ?: throw IOException("Couldn't write $name")
    }

    private fun dirFor(segments: List<String>): DocumentFile {
        dirs[segments]?.let { return it }
        val parent = dirFor(segments.dropLast(1))
        val name = segments.last()
        val dir = parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: throw IOException("Couldn't create folder $name")
        dirs[segments] = dir
        return dir
    }
}
