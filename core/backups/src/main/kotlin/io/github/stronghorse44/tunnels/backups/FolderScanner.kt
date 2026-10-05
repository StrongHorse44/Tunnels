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
 * What the bundles of one app add up to. [files] counts the files whose header (or, for the old Tunnels format, whose
 * file date) was read. [newestMs] is the newest usable date, 0 when there is none; a header dated more than a day
 * ahead of the clock is counted in [suspicious] instead and never wins. [fromFileDate] is set when the newest is an
 * old-format Tunnels export, which has no header date, so the file's last-modified time stands in; an old-format file
 * with no usable last-modified time is counted in [undated]: present, date unknown. [items] counts Lumen's per-item
 * bundles, which are counted by name and never opened (only its manifest bundle says when the export was made).
 */
data class AppSummary(
    val appId: String,
    val files: Int,
    val newestMs: Long,
    val schema: Long,
    val fromFileDate: Boolean,
    val suspicious: Int,
    val items: Int = 0,
    val undated: Int = 0,
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
    /**
     * A bound was hit (more candidate files than [FolderScanner.MAX_HEADERS], or more entries than
     * [FolderScanner.MAX_ENTRIES]), so some files were not read. The picture is incomplete: nothing is judged stale or missing.
     */
    val truncated: Boolean = false,
) {
    val bundleFiles: Int get() = apps.values.sumOf { it.files + it.items } + other.files

    companion object {
        val NONE = FolderScan(FolderState.NONE)
        val LOST = FolderScan(FolderState.LOST)
    }
}

/**
 * Walks the export folder and reads only the header of each `.fwx` or `.tsnap` file ([HeaderReader]). Other files are
 * counted and never opened. Subfolders are followed [MAX_DEPTH] levels down, so a folder per app works.
 *
 * Lumen writes one bundle per vault item (`lumen-<stamp>Z-<n>.fwx`, hundreds of them) next to its manifest bundle
 * (`lumen-<stamp>Z.fwx`): the per-item files are counted by name and not opened. The rest are read newest first (name
 * descending, then last-modified descending), at most [MAX_HEADERS] of them; if more remain the scan is [FolderScan.truncated].
 */
object FolderScanner {
    const val MAX_DEPTH = 2
    const val MAX_HEADERS = 500
    const val MAX_ENTRIES = 20_000

    /** A header dated further ahead than this is suspicious rather than fresh (spec section 6). */
    const val FUTURE_SLACK_MS = 24L * 60 * 60 * 1000

    private val LUMEN_ITEM = Regex("""^lumen-.+z-\d+\.fwx$""", RegexOption.IGNORE_CASE)

    private class Acc {
        var files = 0
        var newest = 0L
        var schema = 0L
        var suspicious = 0
        var legacyNewest = 0L
        var items = 0
        var undated = 0
    }

    fun isCandidate(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fwx") || n.endsWith(".tsnap")
    }

    /** Lumen's per-item bundle: counted, never opened. */
    fun isLumenItem(name: String): Boolean = LUMEN_ITEM.matches(name)

    fun scan(source: FolderSource, nowMs: Long): FolderScan {
        val roots = try {
            source.list(null)
        } catch (_: Exception) {
            return FolderScan.LOST
        }
        val accs = HashMap<String, Acc>()
        val candidates = ArrayList<FolderEntry>()
        var otherFiles = 0
        var otherNewest = 0L
        var unreadable = 0
        var skipped = 0
        var truncated = false
        var entries = 0

        fun collect(listing: List<FolderEntry>, depth: Int) {
            for (e in listing.sortedBy { it.name }) {
                if (truncated) return
                if (++entries > MAX_ENTRIES) { truncated = true; return }
                if (e.isDir) {
                    if (depth < MAX_DEPTH) {
                        val children = try { source.list(e) } catch (_: Exception) { unreadable++; continue }
                        collect(children, depth + 1)
                    } else {
                        skipped++
                    }
                    continue
                }
                when {
                    !isCandidate(e.name) -> skipped++
                    isLumenItem(e.name) -> accs.getOrPut("lumen") { Acc() }.items++
                    else -> candidates += e
                }
            }
        }
        collect(roots, 0)

        // Newest first, so that when there are more files than the bound allows, it is the old ones that are left unread.
        candidates.sortWith(compareByDescending<FolderEntry> { it.name.lowercase() }.thenByDescending { it.modifiedMs })
        if (candidates.size > MAX_HEADERS) truncated = true
        for (e in candidates.take(MAX_HEADERS)) {
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
                    when {
                        e.modifiedMs <= 0 -> a.undated++
                        e.modifiedMs > nowMs + FUTURE_SLACK_MS -> a.suspicious++
                        e.modifiedMs > a.legacyNewest -> a.legacyNewest = e.modifiedMs
                    }
                }
                is HeaderRead.Unreadable -> unreadable++
            }
        }

        val apps = accs.mapValues { (id, a) ->
            val useFileDate = a.legacyNewest > a.newest
            AppSummary(
                appId = id,
                files = a.files,
                newestMs = if (useFileDate) a.legacyNewest else a.newest,
                schema = if (useFileDate) 0L else a.schema,
                fromFileDate = useFileDate,
                suspicious = a.suspicious,
                items = a.items,
                undated = a.undated,
            )
        }
        return FolderScan(FolderState.OK, apps, OtherSummary(otherFiles, otherNewest), unreadable, skipped, truncated)
    }
}
