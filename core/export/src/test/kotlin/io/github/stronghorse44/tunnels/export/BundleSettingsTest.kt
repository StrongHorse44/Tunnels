package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.dns.BlockPolicy
import io.github.stronghorse44.tunnels.dns.Upstream
import io.github.stronghorse44.tunnels.pairing.Pin
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleSettingsTest {
    @Test
    fun theCarriedKeysAreTheOnesTheirOwnersDefine() {
        assertEquals(listOf("traffic.block", "traffic.upstream", "watch.settings"), BundleSettings.KEYS)
        assertEquals(BlockPolicy.KEY, BundleSettings.KEYS[0])
        assertEquals(Upstream.KEY, BundleSettings.KEYS[1])
        assertEquals(WatchSettings.KEY, BundleSettings.KEYS[2])
        // This phone's own history and identity are never carried.
        for (k in listOf("watch.status", "inbox.lastVisit", "pairing.verifierId", "pairing.pins")) assertNull(BundleSettings.canonical(k, "x"))
    }

    @Test
    fun valuesAreWrittenBackInTheirOwnersCanonicalForm() {
        val raw = mapOf<String, String?>(
            "traffic.block" to "on=true;kinds=ADS,NOPE;exempt= com.b , com.a ;extra=1",
            "traffic.upstream" to "provider=custom;url=http://insecure.example/x",
            "watch.settings" to "enabled=true;interval=1;notify=INFO",
            "watch.status" to "run=5",
            "never.set" to null,
        )
        val out = BundleSettings.canonicalSettings(raw)
        assertEquals(setOf("traffic.block", "traffic.upstream", "watch.settings"), out.keys)
        assertEquals(BlockPolicy.decode(raw["traffic.block"]).encode(), out["traffic.block"])
        assertTrue(out["traffic.block"]!!.contains("exempt=com.a,com.b"))
        // A custom resolver that is not an https URL falls back to the network's own, as the app would read it.
        assertEquals(Upstream().encode(), out["traffic.upstream"])
        assertEquals(WatchSettings.decode(raw["watch.settings"]).encode(), out["watch.settings"])
    }

    @Test
    fun settingsTextRoundTripsAndValuesAreEscaped() {
        val text = BundleSettings.encodeSettings(Samples.settings)
        assertEquals(Samples.settings, BundleSettings.parseSettings(text))
        assertTrue(text.startsWith("TSET1\n"))
        assertEquals(emptyMap<String, String>(), BundleSettings.parseSettings(BundleSettings.encodeSettings(emptyMap())))
    }

    @Test
    fun pinsAreOnePerPhoneAndKeepTheLatestAudit() {
        val old = Samples.pin(1, lastAuditMs = 1_700_000_100_000)
        val newer = Samples.pin(1, lastAuditMs = 1_700_000_900_000)
        val canonical = BundleSettings.canonicalPins(Pin.encode(listOf(old, newer, Samples.pin(2))))
        val back = Pin.decode(canonical)
        assertEquals(2, back.size)
        assertEquals(newer, back.first { it.id == old.id })
        assertEquals(canonical, BundleSettings.parsePins(canonical))
        assertEquals("", BundleSettings.canonicalPins(null))
        assertEquals("", BundleSettings.parsePins(""))
    }

    @Test
    fun mergingPinsAddsNewPhonesAndKeepsTheMoreRecentAudit() {
        val here = Pin.encode(listOf(Samples.pin(1, lastAuditMs = 2_000), Samples.pin(2, lastAuditMs = 5_000)))
        val bundle = Pin.encode(listOf(Samples.pin(2, lastAuditMs = 9_000), Samples.pin(3, lastAuditMs = 1_000), Samples.pin(1, lastAuditMs = 100)))
        val (merged, added) = BundleSettings.mergePins(here, bundle)
        val pins = Pin.decode(merged).associateBy { it.id }
        assertEquals(1, added)
        assertEquals(3, pins.size)
        assertEquals(9_000L, pins[Samples.pin(2).id]!!.lastAuditAt.toEpochMilli())
        assertEquals(2_000L, pins[Samples.pin(1).id]!!.lastAuditAt.toEpochMilli())
        assertEquals(1_000L, pins[Samples.pin(3).id]!!.lastAuditAt.toEpochMilli())
        // Into an empty phone, everything is new; nothing bundled is lost.
        assertEquals(3, BundleSettings.mergePins(null, merged).second)
        // Merging the same bundle twice changes nothing.
        assertEquals(0, BundleSettings.mergePins(merged, merged).second)
    }

    @Test
    fun networksRoundTripSortedAndValidNetworksKeepOnlyHashes() {
        val text = BundleSettings.encodeNetworks(Samples.networks)
        assertEquals("0f".repeat(32) + "\n" + "a1".repeat(32) + "\n", text)
        assertEquals(Samples.networks, BundleSettings.parseNetworks(text))
        assertEquals(emptySet<String>(), BundleSettings.parseNetworks(""))
        assertEquals(setOf("0f".repeat(32)), BundleSettings.validNetworks(listOf("0f".repeat(32), "nonsense", "0F".repeat(32))))
    }
}
