package io.github.stronghorse44.tunnels.watch

import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFormatTest {
    private val hour = 3_600_000L

    @Test
    fun agesReadNaturally() {
        assertEquals("just now", WatchFormat.ago(10_000))
        assertEquals("12 min ago", WatchFormat.ago(12 * 60_000L))
        assertEquals("5 h ago", WatchFormat.ago(5 * hour))
        assertEquals("3 days ago", WatchFormat.ago(72 * hour))
        assertEquals("just now", WatchFormat.ago(-5))
    }

    @Test
    fun theStatusLineSaysWhatTheLastCheckDid() {
        val on = WatchSettings(enabled = true, intervalHours = 12)
        assertEquals("Off · no check yet", WatchFormat.statusLine(WatchSettings(), WatchStatus(), scheduled = false, now = 0))
        val quiet = WatchStatus(lastRunAt = 0, tunnels = 4, added = 0, stored = false, reason = "quick check").copy(lastRunAt = 1)
        assertEquals("Every 12 h · last 2 h ago (quick check): 4 tunnels, nothing new, no changes", WatchFormat.statusLine(on, quiet, true, 1 + 2 * hour))
        val busy = WatchStatus(lastRunAt = 1, tunnels = 7, added = 2, stored = true, failed = listOf("silicon"), reason = "apps changed")
        assertEquals("Every 12 h · last just now (apps changed): 7 tunnels, 2 new, failed: Silicon", WatchFormat.statusLine(on, busy, true, 1))
        assertTrue(WatchFormat.statusLine(on, busy, scheduled = false, now = 1).startsWith("On, waiting for Android"))
    }

    @Test
    fun theScopeNamesTheTunnelsFromThePolicy() {
        val scope = WatchFormat.scope()
        assertTrue(scope, scope.contains("Permissions, Trust store, System packages, Silicon"))
        assertTrue(scope.contains("APK excavation, Doors, Hardening audit"))
    }
}
