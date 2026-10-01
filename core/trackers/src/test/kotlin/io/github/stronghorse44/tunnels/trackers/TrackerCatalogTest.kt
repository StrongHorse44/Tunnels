package io.github.stronghorse44.tunnels.trackers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCatalogTest {
    @Test
    fun catalogIsLargeUniqueAndWellFormed() {
        val all = TrackerCatalog.all
        assertTrue("at least 80 trackers, got ${all.size}", all.size >= 80)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val prefixes = all.flatMap { it.classPrefixes }
        assertEquals(prefixes.size, prefixes.toSet().size)
        for (t in all) {
            assertTrue(t.id, t.categories.isNotEmpty())
            assertTrue(t.id, t.name.isNotBlank() && t.vendor.isNotBlank())
            assertTrue(t.id, Regex("[a-z0-9_]+").matches(t.id))
            for (p in t.classPrefixes) {
                assertTrue(p, p.startsWith("L") && p.endsWith("/"))
                assertTrue(p, p.all { it.code in 33..126 })
            }
        }
    }

    @Test
    fun categoriesAreHonest() {
        assertEquals(setOf(TrackerCategory.PUSH), TrackerCatalog.byId("firebase_messaging")!!.categories)
        assertEquals(setOf(TrackerCategory.CRASH), TrackerCatalog.byId("sentry")!!.categories)
        assertEquals(setOf(TrackerCategory.ADS), TrackerCatalog.byId("applovin")!!.categories)
        assertEquals(setOf(TrackerCategory.LOCATION), TrackerCatalog.byId("cuebiq")!!.categories)
        assertTrue(TrackerCategory.ATTRIBUTION in TrackerCatalog.byId("appsflyer")!!.categories)
    }

    @Test
    fun matcherFindsDeepestPrefixAndIgnoresNeighbours() {
        val m = TrackerMatcher.DEFAULT
        assertEquals("firebase_analytics", m.match("Lcom/google/firebase/analytics/FirebaseAnalytics;"))
        assertEquals("google_ad_id", m.match("Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;"))
        assertEquals("google_admob", m.match("Lcom/google/android/gms/ads/AdView;"))
        assertEquals("zendesk", m.match("Lzendesk/core/Zendesk;"))
        assertNull(m.match("Lcom/google/firebase/FirebaseApp;"))
        assertNull(m.match("Lcom/facebook/FacebookSdk;"))
        assertNull(m.match("Lcom/appsflyerclone/X;"))
        assertNull(m.match(""))
        assertNull(m.match("L"))
    }

    @Test
    fun summariseMergesAndSorts() {
        val s = TrackerMatcher.DEFAULT.summarise(
            mapOf("appsflyer" to 2, "sentry" to 1),
            mapOf("appsflyer" to 3, "unknown_tracker" to 9),
        )
        assertEquals(listOf("appsflyer", "sentry"), s.ids)
        assertEquals(5, s.hits.first().classes)
        assertEquals(setOf(TrackerCategory.ATTRIBUTION, TrackerCategory.ANALYTICS, TrackerCategory.CRASH), s.categories)
        assertEquals(TrackerSummary.EMPTY, TrackerMatcher.DEFAULT.summarise(emptyMap()))
    }

    @Test
    fun prefixTrieRejectsBadInput() {
        val trie = PrefixTrie(listOf("Lab/" to 1, "Lab/cd/" to 2, "Lx/" to 3))
        assertEquals(3, trie.size)
        assertEquals(2, trie.longestMatch("Lab/cd/E;"))
        assertEquals(1, trie.longestMatch("Lab/cx/E;"))
        assertNull(trie.longestMatch("Lab"))
        val b = "zzLx/Y;".toByteArray()
        assertEquals(3, trie.longestMatch(b, 2, b.size))
        assertNull(trie.longestMatch(b, 0, b.size))
        assertNull(trie.longestMatch(b, 2, 2))
        try { PrefixTrie(listOf("" to 1)); throw AssertionError("empty prefix accepted") } catch (_: IllegalArgumentException) { }
        try { PrefixTrie(listOf("Lé/" to 1)); throw AssertionError("non-ascii accepted") } catch (_: IllegalArgumentException) { }
    }
}
