package io.github.stronghorse44.tunnels.trackers

/** One recognised SDK and how many of its classes were seen. */
data class TrackerHit(val tracker: Tracker, val classes: Int)

/** Everything found in one app's dex files, deduplicated per tracker. */
data class TrackerSummary(
    val hits: List<TrackerHit>,
    val categories: Set<TrackerCategory>,
) {
    val count: Int get() = hits.size
    val ids: List<String> get() = hits.map { it.tracker.id }

    companion object {
        val EMPTY = TrackerSummary(emptyList(), emptySet())
    }
}

/** Matches dex type descriptors against a [Tracker] catalog. */
class TrackerMatcher(private val catalog: List<Tracker> = TrackerCatalog.all) {
    private val trie = PrefixTrie(catalog.flatMap { t -> t.classPrefixes.map { it to t.id } })

    /** Tracker id owning the descriptor in [bytes] [start, end), or null. */
    fun match(bytes: ByteArray, start: Int, end: Int): String? = trie.longestMatch(bytes, start, end)

    fun match(descriptor: String): String? = trie.longestMatch(descriptor)

    fun tracker(id: String): Tracker? = catalog.firstOrNull { it.id == id }

    /** Merges per-dex hit counts (tracker id to classes) into one summary, sorted by tracker name. */
    fun summarise(vararg hitMaps: Map<String, Int>): TrackerSummary {
        val merged = HashMap<String, Int>()
        for (m in hitMaps) for ((id, n) in m) merged[id] = (merged[id] ?: 0) + n
        return summarise(merged)
    }

    fun summarise(hits: Map<String, Int>): TrackerSummary {
        val list = hits.mapNotNull { (id, n) -> tracker(id)?.let { TrackerHit(it, n) } }.sortedBy { it.tracker.name.lowercase() }
        return TrackerSummary(list, list.flatMap { it.tracker.categories }.toSet())
    }

    companion object {
        /** Built once; the trie is immutable and safe to share across threads. */
        val DEFAULT: TrackerMatcher by lazy { TrackerMatcher() }
    }
}
