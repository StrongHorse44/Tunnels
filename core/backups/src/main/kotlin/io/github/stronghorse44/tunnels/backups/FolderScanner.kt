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

/** Why an app is not judged: [CUT] the scan left files unread, [FAILED] the storage provider failed on a file or folder. */
enum class Hold { CUT, FAILED }

/**
 * What the bundles of one app add up to. [files] counts the files whose header (or, for the old Tunnels format, whose
 * file date) was read. [newestMs] is the newest usable date, 0 when there is none; a header dated more than a day
 * ahead of the clock is counted in [suspicious] instead and never wins. [fromFileDate] is set when the newest is an
 * old-format Tunnels export, which has no header date, so the file's last-modified time stands in; an old-format file
 * with no usable last-modified time is counted in [undated]: present, date unknown. [headerMs] is the newest FWX
 * header date on its own, so a scan can tell an undated old-format file from one that an FWX header outdates. [items] counts Lumen's per-item
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
    /** The newest usable FWX header date, 0 when the app has none. An FWX header is always a later export than any old-format file. */
    val headerMs: Long = 0L,
    /** The file that sets [newestMs] is named for another registry app than the one its header (or format) says. */
    val newestMisnamed: Boolean = false,
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
     * A bound was hit (more candidates than [FolderScanner.MAX_HEADERS] or [FolderScanner.MAX_PER_APP] for one app name,
     * or more entries than [FolderScanner.MAX_ENTRIES]), so some files were not read. Which apps that stops us judging
     * is [held] and [heldAll]: an app whose newest-named files were read is still judged.
     */
    val truncated: Boolean = false,
    /** Files or folders the storage provider failed on (could not be opened or listed). Never silent: counted here. */
    val faults: Int = 0,
    /** Apps that cannot be judged stale or missing, because a file that might be theirs was not read or could not be read. */
    val held: Map<String, Hold> = emptyMap(),
    /** Set when what was not read could belong to any app (an unnamed pile, a folder that did not list, a cut listing). */
    val heldAll: Hold? = null,
    /** Apps held by a failure on a file whose own name says it is theirs ("a Prikey file could not be read"), not by an unnamed one. */
    val failedNamed: Set<String> = emptySet(),
) {
    val bundleFiles: Int get() = apps.values.sumOf { it.files + it.items } + other.files

    /** Why [appId] cannot be judged by this scan, or null when it can. A failure outranks a bound. */
    fun holdOf(appId: String): Hold? = when {
        held[appId] == Hold.FAILED || heldAll == Hold.FAILED -> Hold.FAILED
        held[appId] != null || heldAll != null -> Hold.CUT
        else -> null
    }

    companion object {
        val NONE = FolderScan(FolderState.NONE)
        val LOST = FolderScan(FolderState.LOST)
    }
}

/**
 * Walks the export folder and reads only the header of each `.fwx` or `.tsnap` file ([HeaderReader]). Other files are
 * counted and never opened. Subfolders are followed [MAX_DEPTH] levels down, so a folder per app works.
 *
 * Lumen writes one bundle per vault item (`lumen-<yyyyMMdd>-<HHmmss>Z-<n>.fwx`, hundreds of them) next to its manifest
 * bundle (`lumen-<yyyyMMdd>-<HHmmss>Z.fwx`): the per-item files are counted by name and not opened.
 *
 * What is read: candidates are grouped by folder and app name (the name's leading letters, or the registry app ID it
 * starts with), newest name first in each group. At most [MAX_PER_APP] per group are eligible, taken one rank at a time
 * across the groups until [MAX_HEADERS] are read, registry apps first. One app's pile of exports cannot starve another.
 *
 * What it means when some were not read (or a file failed): a group is harmless when its newest-named candidate was read
 * and every header read in it names one app (the group's own app, if the name says so): the unread ones are older exports
 * of the same app, so that app is still judged from its newest. Anything else holds the app the name points to, or all
 * apps when the name points to none. A held app is not judged; it is never called missing or stale.
 *
 * A group whose reads have all dated nothing (every header in the future, or old-format files with no modified time) is
 * read past [MAX_PER_APP] (up to [MAX_HEADERS] in all) until a read gives a date; if names are still unread then, the group
 * is not harmless either: it holds the app its headers name when they all name one and the group's name names none, else
 * the app the name points to (or every app, for an unnamed pile of mixed headers).
 */
object FolderScanner {
    const val MAX_DEPTH = 2
    const val MAX_HEADERS = 500
    const val MAX_PER_APP = 64
    const val MAX_ENTRIES = 20_000

    /** A header dated further ahead than this is suspicious rather than fresh (spec section 6). */
    const val FUTURE_SLACK_MS = 24L * 60 * 60 * 1000

    /** Lumen's per-item bundle, exactly as the export spec writes it. Anything else is read like any other candidate. */
    private val LUMEN_ITEM = Regex("""^lumen-\d{8}-\d{6}Z-\d+\.fwx$""")

    private const val OTHER_APP = "?"
    private val IDS_LONGEST_FIRST = BackupApps.all.map { it.id }.sortedByDescending { it.length }

    private class Acc {
        var files = 0
        var newest = 0L
        var schema = 0L
        var suspicious = 0
        var legacyNewest = 0L
        var items = 0
        var undated = 0
        var newestMisnamed = false
        var legacyMisnamed = false
    }

    private enum class Outcome { NOT_READ, BUNDLE, NOT_A_BUNDLE, FAILED }

    private class Cand(val entry: FolderEntry) {
        var outcome = Outcome.NOT_READ
        var app: String? = null
        var otherMs = 0L

        /** The read gave a usable date. A header dated in the future, and an old-format file with no modified time, give none. */
        var dates = false
    }

    private class Group(val key: String, val app: String?) {
        val cands = ArrayList<Cand>()
    }

    fun isCandidate(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".fwx") || n.endsWith(".tsnap")
    }

    /** Lumen's per-item bundle: counted, never opened. */
    fun isLumenItem(name: String): Boolean = LUMEN_ITEM.matches(name)

    /** The registry app a file name starts with (longest ID first, so `pusher-server` is not `pusher`), else null. */
    internal fun appOfName(name: String): String? {
        val n = name.lowercase()
        return IDS_LONGEST_FIRST.firstOrNull { n.startsWith(it) && (n.length == it.length || !n[it.length].isLetter()) }
    }

    /** The file's name points to a registry app other than the one its content says. */
    private fun misnamed(name: String, appId: String): Boolean = appOfName(name)?.let { it != appId } ?: false

    private fun prefixOf(name: String): String = appOfName(name) ?: name.lowercase().takeWhile { it in 'a'..'z' }

    fun scan(source: FolderSource, nowMs: Long): FolderScan {
        val roots = try {
            source.list(null)
        } catch (_: Exception) {
            return FolderScan.LOST
        }
        val accs = HashMap<String, Acc>()
        val groups = LinkedHashMap<String, Group>()
        var otherFiles = 0
        var otherNewest = 0L
        var unreadable = 0
        var faults = 0
        var skipped = 0
        var cut = false
        var entries = 0
        var heldAll: Hold? = null
        val held = HashMap<String, Hold>()

        fun holdAll(h: Hold) { if (heldAll != Hold.FAILED) heldAll = h }
        fun hold(app: String, h: Hold) { if (held[app] != Hold.FAILED) held[app] = h }

        fun collect(listing: List<FolderEntry>, depth: Int, dir: String) {
            for (e in listing.sortedBy { it.name }) {
                if (cut) return
                if (++entries > MAX_ENTRIES) { cut = true; return }
                if (e.isDir) {
                    if (depth < MAX_DEPTH) {
                        val children = try { source.list(e) } catch (_: Exception) { unreadable++; faults++; holdAll(Hold.FAILED); continue }
                        collect(children, depth + 1, e.id)
                    } else {
                        skipped++
                    }
                    continue
                }
                when {
                    !isCandidate(e.name) -> skipped++
                    isLumenItem(e.name) -> accs.getOrPut("lumen") { Acc() }.items++
                    else -> {
                        val prefix = prefixOf(e.name)
                        groups.getOrPut("$dir\u0000$prefix") { Group("$dir\u0000$prefix", appOfName(e.name)) }.cands += Cand(e)
                    }
                }
            }
        }
        collect(roots, 0, "")
        if (cut) holdAll(Hold.CUT)

        // Newest names first within a group; the registry apps' groups go first in every round, then the others newest first.
        for (g in groups.values) {
            g.cands.sortWith(compareByDescending<Cand> { it.entry.name.lowercase() }.thenByDescending { it.entry.modifiedMs })
        }
        val order = groups.values.sortedWith(compareBy<Group> { it.app == null }.thenByDescending { it.key })
        var reads = 0
        rounds@ for (rank in 0 until MAX_PER_APP) {
            var any = false
            for (g in order) {
                val c = g.cands.getOrNull(rank) ?: continue
                any = true
                if (reads >= MAX_HEADERS) break@rounds
                reads++
                read(source, c, nowMs, accs)
                when {
                    c.outcome == Outcome.FAILED -> { faults++; unreadable++ }
                    c.outcome == Outcome.NOT_A_BUNDLE -> unreadable++
                    c.app == OTHER_APP -> { otherFiles++; if (c.otherMs > otherNewest) otherNewest = c.otherMs }
                }
            }
            if (!any) break
        }

        // A group whose every header so far is dated in the future has told us no date, so its unread older names are not
        // "older exports of the same app": keep reading it, one group after another, until a header gives a usable date or
        // the global bound is reached. What is still unread after that holds the app (see below).
        for (g in order) {
            while (reads < MAX_HEADERS && onlySuspicious(g)) {
                val c = g.cands.firstOrNull { it.outcome == Outcome.NOT_READ } ?: break
                reads++
                read(source, c, nowMs, accs)
                when {
                    c.outcome == Outcome.FAILED -> { faults++; unreadable++ }
                    c.outcome == Outcome.NOT_A_BUNDLE -> unreadable++
                    c.app == OTHER_APP -> { otherFiles++; if (c.otherMs > otherNewest) otherNewest = c.otherMs }
                }
            }
        }

        var truncated = cut
        val failedNamed = HashSet<String>()
        for (g in groups.values) {
            val shaky = g.cands.any { it.outcome == Outcome.NOT_READ || it.outcome == Outcome.FAILED }
            if (!shaky) continue
            truncated = truncated || g.cands.any { it.outcome == Outcome.NOT_READ }
            val first = g.cands.first { it.outcome != Outcome.NOT_A_BUNDLE }
            val apps = g.cands.filter { it.outcome == Outcome.BUNDLE }.mapNotNull { it.app }.toSet()
            // Unread names are older exports only if what was read in the group did date something. A pile of another
            // app's exports needs no date: it is not counted.
            val only = apps.singleOrNull()
            val undated = nothingDated(g)
            val harmless = first.outcome == Outcome.BUNDLE && only != null && (g.app == null || only == g.app) && (!undated || only == OTHER_APP)
            if (harmless) continue
            val why = if (g.cands.any { it.outcome == Outcome.FAILED }) Hold.FAILED else Hold.CUT
            if (g.app == null) {
                // An unnamed pile whose every header read names one app (and dated nothing) is that app's: only it is held.
                if (undated && why == Hold.CUT && only != null && only != OTHER_APP) hold(only, why) else holdAll(why)
            } else {
                if (why == Hold.FAILED) failedNamed += g.app
                hold(g.app, why)
                apps.filter { it != OTHER_APP }.forEach { hold(it, why) }
            }
        }

        val summaries = accs.mapValues { (id, a) ->
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
                headerMs = a.newest,
                newestMisnamed = if (useFileDate) a.legacyMisnamed else a.newestMisnamed,
            )
        }
        return FolderScan(FolderState.OK, summaries, OtherSummary(otherFiles, otherNewest), unreadable, skipped, truncated, faults, held, heldAll, failedNamed)
    }

    /** Bundles were read in [g] and none of them gave a usable date (all future-dated, or old-format files with no modified time). */
    private fun nothingDated(g: Group): Boolean {
        val bundles = g.cands.filter { it.outcome == Outcome.BUNDLE }
        return bundles.isNotEmpty() && bundles.none { it.dates }
    }

    /** [g] should be read further: nothing in it has dated anything yet, nothing failed, and names are left. */
    private fun onlySuspicious(g: Group): Boolean =
        nothingDated(g) && g.cands.none { it.outcome == Outcome.FAILED } && g.cands.any { it.outcome == Outcome.NOT_READ }

    /** Reads one candidate's header into [accs] and notes the outcome on [c]. */
    private fun read(source: FolderSource, c: Cand, nowMs: Long, accs: HashMap<String, Acc>) {
        val e = c.entry
        when (val r = HeaderReader.read { source.open(e) }) {
            is HeaderRead.Bundle -> {
                c.outcome = Outcome.BUNDLE
                if (BackupApps.isKnown(r.appId)) {
                    c.app = r.appId
                    val a = accs.getOrPut(r.appId) { Acc() }
                    a.files++
                    if (r.createdMs > nowMs + FUTURE_SLACK_MS) {
                        a.suspicious++
                    } else {
                        c.dates = true
                        if (r.createdMs > a.newest) {
                            a.newest = r.createdMs; a.schema = r.schema
                            a.newestMisnamed = misnamed(e.name, r.appId)
                        }
                    }
                } else {
                    c.app = OTHER_APP
                    if (r.createdMs <= nowMs + FUTURE_SLACK_MS) { c.otherMs = r.createdMs; c.dates = true }
                }
            }
            HeaderRead.Legacy -> {
                c.outcome = Outcome.BUNDLE
                c.app = "tunnels"
                val a = accs.getOrPut("tunnels") { Acc() }
                a.files++
                when {
                    e.modifiedMs <= 0 -> a.undated++
                    e.modifiedMs > nowMs + FUTURE_SLACK_MS -> a.suspicious++
                    else -> {
                        c.dates = true
                        if (e.modifiedMs > a.legacyNewest) { a.legacyNewest = e.modifiedMs; a.legacyMisnamed = misnamed(e.name, "tunnels") }
                    }
                }
            }
            is HeaderRead.Unreadable -> c.outcome = if (r.access) Outcome.FAILED else Outcome.NOT_A_BUNDLE
        }
    }
}
