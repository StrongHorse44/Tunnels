package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsAndCompaniesTest {
    private val hosts = HostList.parse(
        """
        # AdAway default blocklist
        127.0.0.1 localhost
        ::1 localhost
        127.0.0.1  ads.example.com  # trailing comment
        0.0.0.0 track.example.net
        ::1 v6.example.org
        bare.example.io
        127.0.0.1 10.0.0.1
        127.0.0.1 not_ok!.example.com
        """.trimIndent(),
    )

    @Test
    fun hostsFilesBlockExactHostsOnly() {
        assertEquals(4, hosts.size)
        assertTrue("ADS.example.com." in hosts)
        assertTrue("track.example.net" in hosts && "v6.example.org" in hosts && "bare.example.io" in hosts)
        assertFalse("hosts entries do not cover subdomains", "x.ads.example.com" in hosts)
        assertFalse("or parents", "example.com" in hosts)
        assertFalse("localhost" in hosts)
        assertFalse("" in HostList.EMPTY)
    }

    @Test
    fun bundledListsApplyOnlyWhenChosenAndBlockingIsOn() {
        val listed: (String, Set<String>) -> String? = { h, ids -> if ("adaway" in ids && h in hosts) "adaway" else null }
        val off = BlockPolicy(enabled = false, lists = setOf("adaway"))
        assertNull(off.blocks("com.app", "ads.example.com", listed = listed))
        val on = BlockPolicy(enabled = true)
        assertNull("not chosen", on.blocks("com.app", "ads.example.com", listed = listed))
        val withList = on.copy(lists = setOf("adaway"))
        val hit = withList.blocks("com.app", "ads.example.com", listed = listed)!!
        assertEquals("ads.example.com", hit.domain)
        assertEquals("AdAway list", hit.vendor)
        assertEquals(TrackerKind.ADS, hit.kind)
        assertNull(withList.copy(exempt = setOf("com.app")).blocks("com.app", "ads.example.com", listed = listed))
        assertTrue(withList.needsLists && !off.needsLists)
    }

    @Test
    fun blockAllCoversEveryKindAndListEvenWithBlockingOff() {
        val listed: (String, Set<String>) -> String? = { h, ids -> if ("adaway" in ids && h in hosts) "adaway" else null }
        val p = BlockPolicy(enabled = false).blockingAll("com.chatty", true)
        assertEquals("graph.facebook.com", p.blocks("com.chatty", "graph.facebook.com")?.domain)
        assertEquals("onesignal.com", p.blocks("com.chatty", "onesignal.com")?.domain)
        assertEquals("AdAway list", p.blocks("com.chatty", "track.example.net", listed = listed)?.vendor)
        assertNull("other apps are untouched", p.blocks("com.other", "ad.doubleclick.net"))
        assertTrue(p.needsLists)
        // Block-all and let-through exclude each other.
        val exempted = p.exempting("com.chatty", true)
        assertTrue("com.chatty" in exempted.exempt && "com.chatty" !in exempted.strict)
        assertTrue("com.chatty" in exempted.blockingAll("com.chatty", true).strict)
        assertTrue("com.chatty" !in exempted.blockingAll("com.chatty", true).exempt)
    }

    @Test
    fun listsAndBlockAllRoundTripAndUnknownListsAreDropped() {
        val p = BlockPolicy(enabled = true, lists = setOf("adaway"), strict = setOf("b.app", "a.app"))
        assertEquals("on=true;kinds=ADS,ANALYTICS,ATTRIBUTION,CRASH,TELEMETRY;exempt=;lists=adaway;strict=a.app,b.app", p.encode())
        assertEquals(p, BlockPolicy.decode(p.encode()))
        assertEquals(emptySet<String>(), BlockPolicy.decode("on=true;lists=gone").lists)
        assertEquals("older values have neither field", BlockPolicy(enabled = true), BlockPolicy.decode("on=true;kinds=ADS,ANALYTICS,ATTRIBUTION,CRASH,TELEMETRY;exempt="))
    }

    @Test
    fun trackersAreGroupedByTheCompanyBehindThem() {
        assertEquals("Google", TrackerDomains.companyOf("Google Firebase"))
        assertEquals("Meta", TrackerDomains.companyOf("Meta Audience Network"))
        assertEquals("Sift", TrackerDomains.companyOf("Sift (device fingerprinting)"))
        assertEquals("New Relic", TrackerDomains.companyOf("New Relic"))
        val grouped = TrackerDomains.companies(listOf("app-measurement.com", "graph.facebook.com", "doubleclick.net", "unlisted.example"))
        assertEquals(listOf("Google" to listOf("app-measurement.com", "doubleclick.net"), "Meta" to listOf("graph.facebook.com")), grouped)
    }
}
