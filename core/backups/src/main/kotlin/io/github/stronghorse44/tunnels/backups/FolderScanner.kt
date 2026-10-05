package io.github.stronghorse44.tunnels.backups

import java.io.IOException
import java.io.InputStream

/** One file or folder in the export folder. [id] is the source's own handle (a document id, a path). */
data class FolderEntry(val id: String, val name: String, val isDir: Boolean, val modifiedMs: Long = 0L, val length: Long = 0L)

/** The export folder as the scanner sees it: a listing and a way to open one file. The phone's side is SAF. */
interface FolderSource {
    /** The children of [dir], or of the picked folder when null. Throws [IOException] when it cannot be listed. */
    fun list(dir: FolderEntry?): List<FolderEntry>

    fun open(file: FolderEntry): InputStream
}

enum class FolderState(val wire: String) {
    /** No folder picked yet. */
    NONE("none"),

    /** The folder was read. */
    OK("ok"),

    /** A folder was picked but cannot be read now (grant dropped, folder moved or deleted). */
    LOST("lost"),
    ;

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What the bundles of one app add up to. [newestMs] is the newest usable date, 0 when there is none; a header dated
 * more than a day ahead of the clock is counted in [suspicious] instead and never wins. [fromFileDate] is set when
 * the newest is an old-format Tunnels export, which has no header date, so the file's last-modified time stands in.
 */
data class AppSummary(
    val appId: String,
    val files: Int,
    val newestMs: Long,
    val schema: Long,
    val fromFileDate: Boolean,
    val suspicious: Int,
)

/** Bundles whose app ID is not in the registry. Only counted, never named. */
data class OtherSummary(val files: Int = 0, val newestMs: Long = 0L)

data class FolderScan(
    val state: FolderState,
    val apps: Map<String, AppSummary> = emptyMap(),
    val other: OtherSummary = OtherSummary(),
    /** Candidate files whose header could not be read as FWX v1. */
    val unreadable: Int = 0,
    /** Files and folders not opened at all (not named .fwx or .tsnap, or deeper than [FolderScanner.MAX_DEPTH]). */
    val skipped: Int = 0,
    /** A bound was hit (too many files), so the picture may be incomplete. */
    val truncated: Boolean = false,
) {
    val bundleFiles: Int get() = apps.values.sumOf { it.files } + other.files

    companion object {
        val NONE = FolderScan(FolderState.NONE)
        val LOST = FolderScan(FolderState.LOST)
    }
}

/**
 * Walks the export folder and reads only the header of each `.fwx` or `.tsnap` file ([HeaderReader]). Other files are
 * counted and never opened. Subfolders are followed [MAX_DEPTH] levels down, so a folder per app works.
 */
object FolderScanner {
    const val MAX_DEPTH = 2
    const val MAX_HEADERS = 500
    const val MAX_ENTRIES = 5_000

    /** A header dated further ahead than this is suspicious rather than fresh (spec section 6). */
    const val FUTURE_SLACK_MS = 24L * 60 * 60 * 1000

    private class Acc {
        var files = 0
        var newest = 0L
        var schema = 0L
        var suspicious = 0
        var legacyFiles = 0
        var legacyNewest = 0L
    }

    fun isCandidate(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fwx") || n.endsWith(".tsnap")
    }

    fun scan(source: FolderSource, nowMs: Long): FolderScan {
        val roots = try {
            source.list(null)
        } catch (_: IOException) {
            return FolderScan.LOST
        } catch (_: SecurityException) {
            return FolderScan.LOST
        }
        val accs = HashMap<String, Acc>()
        var otherFiles = 0
        var otherNewest = 0L
        var unreadable = 0
        var skipped = 0
        var truncated = false
        var headers = 0
        var entries = 0

        fun visit(listing: List<FolderEntry>, depth: Int) {
            for (e in listing.sortedBy { it.name }) {
                if (truncated) return
                if (++entries > MAX_ENTRIES) { truncated = true; return }
                if (e.isDir) {
                    if (depth < MAX_DEPTH) {
                        val children = try { source.list(e) } catch (_: IOException) { unreadable++; continue } catch (_: SecurityException) { unreadable++; continue }
                        visit(children, depth + 1)
                    } else {
                        skipped++
                    }
                    continue
                }
                if (!isCandidate(e.name)) { skipped++; continue }
                if (++headers > MAX_HEADERS) { truncated = true; return }
                when (val r = HeaderReader.read { source.open(e) }) {
                    is HeaderRead.Bundle -> {
                        if (BackupApps.isKnown(r.appId)) {
                            val a = accs.getOrPut(r.appId) { Acc() }
                            a.files++
                            if (r.createdMs > nowMs + FUTURE_SLACK_MS) a.suspicious++
                            else if (r.createdMs > a.newest) { a.newest = r.createdMs; a.schema = r.schema }
                        } else {
                            otherFiles++
                            if (r.createdMs <= nowMs + FUTURE_SLACK_MS && r.createdMs > otherNewest) otherNewest = r.createdMs
                        }
                    }
                    HeaderRead.Legacy -> {
                        val a = accs.getOrPut("tunnels") { Acc() }
                        a.files++
                        a.legacyFiles++
                        if (e.modifiedMs > nowMs + FUTURE_SLACK_MS) a.suspicious++
                        else if (e.modifiedMs > a.legacyNewest) a.legacyNewest = e.modifiedMs
                    }
                    is HeaderRead.Unreadable -> unreadable++
                }
            }
        }
        visit(roots, 0)

        val apps = accs.mapValues { (id, a) ->
            val useFileDate = a.legacyNewest > a.newest
            AppSummary(
                appId = id,
                files = a.files,
                newestMs = if (useFileDate) a.legacyNewest else a.newest,
                schema = if (useFileDate) 0L else a.schema,
                fromFileDate = useFileDate,
                suspicious = a.suspicious,
            )
        }
        return FolderScan(FolderState.OK, apps, OtherSummary(otherFiles, otherNewest), unreadable, skipped, truncated)
    }
}
