package io.github.stronghorse44.tunnels.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTrackerTest {
    @Test
    fun firstPostCountsUpdatesDependOnFlags() {
        val t = PostTracker()
        assertTrue("new key counts", t.shouldCount("k1", ongoing = false, alertOnce = false))
        assertTrue("re-alerting update counts", t.shouldCount("k1", ongoing = false, alertOnce = false))
        assertFalse("alert-once update is quiet", t.shouldCount("k1", ongoing = false, alertOnce = true))
        assertTrue("an ongoing notification counts the first time", t.shouldCount("k2", ongoing = true, alertOnce = false))
        assertFalse("its progress updates do not", t.shouldCount("k2", ongoing = true, alertOnce = false))
        t.removed("k2")
        assertTrue("re-posted after dismissal counts again", t.shouldCount("k2", ongoing = true, alertOnce = false))
    }

    @Test
    fun seenMarksShowingWithoutCounting() {
        val t = PostTracker()
        t.seen("media")
        assertFalse(t.shouldCount("media", ongoing = true, alertOnce = false))
        t.clear()
        assertTrue(t.shouldCount("media", ongoing = true, alertOnce = false))
    }

    @Test
    fun boundedToCapacity() {
        val t = PostTracker(capacity = 3)
        listOf("a", "b", "c", "d").forEach { t.shouldCount(it, ongoing = true, alertOnce = false) }
        assertEquals(3, t.size)
        assertTrue("the oldest key was evicted and counts as new again", t.shouldCount("a", ongoing = true, alertOnce = false))
        assertFalse("recent keys are still tracked", t.shouldCount("d", ongoing = true, alertOnce = false))
    }
}
