package io.github.stronghorse44.tunnels.notifications

/**
 * Decides whether a posted notification is a new post or a quiet update of one already showing.
 * Android calls onNotificationPosted again for every update (progress bars, media players, timers),
 * which would inflate "how often" without the user noticing anything. A re-post of a key that is still
 * showing counts only when it can alert again: not ongoing and not flagged only-alert-once.
 *
 * Holds notification keys only, bounded to [capacity] most recently seen. Thread-safe.
 */
class PostTracker(private val capacity: Int = DEFAULT_CAPACITY) {
    private val active = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /** Marks [key] as showing and says whether this post should be counted. */
    @Synchronized
    fun shouldCount(key: String, ongoing: Boolean, alertOnce: Boolean): Boolean {
        val wasShowing = active.put(key, true) != null
        if (!wasShowing) return true
        return !ongoing && !alertOnce
    }

    /** Records that [key] is already showing (e.g. when the listener connects) without counting it. */
    @Synchronized
    fun seen(key: String) {
        active[key] = true
    }

    @Synchronized
    fun removed(key: String) {
        active.remove(key)
    }

    @Synchronized
    fun clear() = active.clear()

    @get:Synchronized
    val size: Int get() = active.size

    companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}
