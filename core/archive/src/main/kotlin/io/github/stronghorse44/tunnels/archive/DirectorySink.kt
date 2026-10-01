package io.github.stronghorse44.tunnels.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Extracts into a local directory, refusing anything that resolves outside it. */
class DirectorySink(private val root: File) : ExtractSink {
    private val rootPath = root.canonicalPath + File.separator

    override fun directory(segments: List<String>) {
        val dir = resolve(segments)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.name}")
    }

    override fun file(segments: List<String>): OutputStream {
        val target = resolve(segments)
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    fun resolve(segments: List<String>): File {
        val target = File(root, segments.joinToString(File.separator))
        if (!target.canonicalPath.startsWith(rootPath)) throw IOException("Path escapes destination")
        return target
    }
}
