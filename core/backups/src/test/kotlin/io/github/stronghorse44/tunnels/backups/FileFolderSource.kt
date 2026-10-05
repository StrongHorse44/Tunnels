package io.github.stronghorse44.tunnels.backups

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** A [FolderSource] over a plain directory, for the tests. (The emulator test carries its own copy.) */
class FileFolderSource(private val root: File) : FolderSource {
    override fun list(dir: FolderEntry?): List<FolderEntry> {
        val d = if (dir == null) root else File(dir.id)
        val files = d.listFiles() ?: throw IOException("cannot list ${d.name}")
        return files.map { FolderEntry(it.absolutePath, it.name, it.isDirectory, it.lastModified(), it.length()) }
    }

    override fun open(file: FolderEntry): InputStream = FileInputStream(File(file.id))
}
