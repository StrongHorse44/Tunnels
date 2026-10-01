package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerDomainsTest {
    @Test
    fun listIsLargeCleanAndUnique() {
        assertTrue("${TrackerDomains.all.size} entries", TrackerDomains.all.size >= 150)
        val domains = TrackerDomains.all.map { it.domain }
        assertEquals("duplicates", domains.size, domains.toSet().size)
        for (d in domains) {
            assertEquals(d, d.lowercase().trim())
            assertTrue(d, d.contains('.'))
            assertFalse(d, d.endsWith("."))
        }
        assertTrue(TrackerDomains.all.all { it.vendor.isNotBlank() })
    }

    @Test
    fun registrableEntriesAreRegistrableAndHostEntriesAreNot() {
        for (t in TrackerDomains.all) {
            val reg = PublicSuffix.registrableDomain(t.domain)
            if (t.exactHost) {
                assertNotEquals("${t.domain} should be a registrable entry", reg, t.domain)
            } else {
                assertEquals("${t.domain} is not a registrable domain", reg, t.domain)
            }
        }
    }

    @Test
    fun matchesByRegistrableDomain() {
        assertEquals("google-analytics.com", TrackerDomains.match("www.google-analytics.com")!!.domain)
        assertEquals("doubleclick.net", TrackerDomains.match("ad.doubleclick.net")!!.domain)
        assertEquals("app-measurement.com", TrackerDomains.match("app-measurement.com")!!.domain)
        assertEquals("appsflyer.com", TrackerDomains.match("conversions.appsflyer.com")!!.domain)
        assertEquals("branch.io", TrackerDomains.match("api2.branch.io")!!.domain)
        assertEquals("sentry.io", TrackerDomains.match("o12345.ingest.sentry.io")!!.domain)
        assertEquals(TrackerKind.CRASH, TrackerDomains.match("o12345.ingest.sentry.io")!!.kind)
    }

    @Test
    fun matchesByExactHostIncludingSubdomains() {
        assertEquals("graph.facebook.com", TrackerDomains.match("graph.facebook.com")!!.domain)
        assertEquals("graph.facebook.com", TrackerDomains.match("edge.graph.facebook.com")!!.domain)
        assertEquals("firebaseinstallations.googleapis.com", TrackerDomains.match("firebaseinstallations.googleapis.com")!!.domain)
        assertEquals("firebaseinstallations.googleapis.com", TrackerDomains.match("FirebaseInstallations.GoogleAPIs.com.")!!.domain)
        assertEquals("unityads.unity3d.com", TrackerDomains.match("auction.unityads.unity3d.com")!!.domain)
    }

    @Test
    fun innocentHostsUnderTheSameDomainsDoNotMatch() {
        assertNull(TrackerDomains.match("www.facebook.com"))
        assertNull(TrackerDomains.match("www.googleapis.com"))
        assertNull(TrackerDomains.match("oauth2.googleapis.com"))
        assertNull(TrackerDomains.match("www.google.com"))
        assertNull(TrackerDomains.match("api.github.com"))
        assertNull(TrackerDomains.match("example.com"))
        assertNull(TrackerDomains.match(""))
        assertFalse(TrackerDomains.isTracker("notsentry.io"))
    }

    @Test
    fun lookupByDomain() {
        assertEquals("Meta", TrackerDomains.byDomain("graph.facebook.com")!!.vendor)
        assertEquals("Adjust", TrackerDomains.byDomain("adjust.com")!!.vendor)
        assertNull(TrackerDomains.byDomain("nothing.example"))
    }
}
