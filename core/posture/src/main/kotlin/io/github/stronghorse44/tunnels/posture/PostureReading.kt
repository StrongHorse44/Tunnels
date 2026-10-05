package io.github.stronghorse44.tunnels.posture

/** How one posture item came out. UNKNOWN is never a finding. */
enum class PostureState(val word: String) {
    GOOD("good"),
    WEAK("weak"),
    UNKNOWN("unknown"),
    NA("n/a"),
    ;

    companion object {
        fun of(word: String?): PostureState? = entries.firstOrNull { it.word == word }
    }
}

/** Why an item is unknown. */
enum class PostureWhy(val word: String) {
    /** The key is not in a table that read fine: never written, the OS uses its default. */
    ABSENT("absent"),

    /** The table or command could not be read this scan. */
    UNREADABLE("unreadable"),

    /** The key is there with a value Tunnels does not understand. */
    MALFORMED("malformed"),

    /** The key reads fine but its name is not confirmed on this phone yet. */
    UNCONFIRMED("unconfirmed"),
    ;

    companion object {
        fun of(word: String?): PostureWhy? = entries.firstOrNull { it.word == word }
    }
}

/**
 * One item's reading. [value] is the normalised value ("12 h", "off"): set when the item was read, whatever the
 * state, and never set for an absent, unreadable or malformed key. [why] is set only for UNKNOWN.
 */
data class Reading(val item: PostureItem, val state: PostureState, val value: String?, val why: PostureWhy?)

/** A scan's readings and how each command read ("ok" or its failure word). */
data class PostureSnapshot(val readings: List<Reading>, val reads: Map<PostureTable, String>) {
    fun reading(id: String): Reading? = readings.firstOrNull { it.item.id == id }
    val isEmpty: Boolean get() = readings.isEmpty()
}
