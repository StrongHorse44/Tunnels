package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxRowsTest {
    private val t0 = Instant.parse("2026-10-10T12:00:00Z")

    private fun f(tunnel: String, kind: String, subject: String, severity: Severity = Severity.NOTICE, minutesAgo: Long = 0, firstMinutesAgo: Long = 60) = Finding(
        tunnelId = tunnel, subject = subject, kind = kind, severity = severity,
        firstSeen = t0.minusSeconds(firstMinutesAgo * 60), lastSeen = t0.minusSeconds(minutesAgo * 60), evidence = "", actions = emptyList(),
    )

    @Test
    fun threeOrMoreOfOneKindFoldIntoOneRow() {
        val wifi = (1..14).map { f("surroundings", "WIFI_AP_REPEATED", "wifiap:$it", minutesAgo = it.toLong()) }
        val rows = InboxRows.rows(wifi + f("permissions", "PERMISSION_GAINED", "a.b"), lastVisit = null)
        assertEquals(2, rows.size)
        val group = rows.filterIsInstance<InboxRow.Group>().single()
        assertEquals("14 Wi-Fi access points keep turning up", group.title)
        assertEquals("wifiap:1", group.findings.first().subject)
        assertEquals("group|surroundings|WIFI_AP_REPEATED", group.key)
    }

    @Test
    fun twoOfAKindStaySeparate() {
        val rows = InboxRows.rows(listOf(f("homenet", "RISKY_SERVICE", "a"), f("homenet", "RISKY_SERVICE", "b")), null)
        assertTrue(rows.all { it is InboxRow.Single })
    }

    @Test
    fun theSameKindFromTwoTunnelsIsTwoGroups() {
        val rows = InboxRows.rows((1..3).map { f("a", "K", "x$it") } + (1..3).map { f("b", "K", "y$it") }, null)
        assertEquals(setOf("group|a|K", "group|b|K"), rows.map { it.key }.toSet())
    }

    @Test
    fun aGroupSitsWhereItsMostSevereFindingWould() {
        val notices = (1..5).map { f("surroundings", "WIFI_AP_REPEATED", "n$it", Severity.NOTICE, minutesAgo = 1) }
        val warn = f("surroundings", "WIFI_AP_FOLLOWING", "w", Severity.WARN, minutesAgo = 300)
        val mixed = (1..3).map { f("apk", "TRACKER_SDK", "app$it", if (it == 1) Severity.CRITICAL else Severity.INFO, minutesAgo = 600) }
        val rows = InboxRows.rows(notices + warn + mixed, null)
        assertEquals(listOf(Severity.CRITICAL, Severity.WARN, Severity.NOTICE), rows.map { it.severity })
        val apps = rows.first() as InboxRow.Group
        assertEquals(Severity.CRITICAL, apps.findings.first().severity)
    }

    @Test
    fun newCountsFindingsFirstSeenSinceTheLastVisit() {
        val visit = t0.minusSeconds(30 * 60)
        val list = listOf(
            f("s", "WIFI_AP_REPEATED", "old", firstMinutesAgo = 90),
            f("s", "WIFI_AP_REPEATED", "new1", firstMinutesAgo = 10),
            f("s", "WIFI_AP_REPEATED", "new2", firstMinutesAgo = 5),
        )
        assertEquals(2, (InboxRows.rows(list, visit).single() as InboxRow.Group).newCount)
        assertEquals(0, (InboxRows.rows(list, null).single() as InboxRow.Group).newCount)
    }

    @Test
    fun unknownKindsAreNamedInWords() {
        assertEquals("4 × Permission lost", InboxRows.title("PERMISSION_LOST", 4))
        assertEquals("3 unknown devices on your network", InboxRows.title("UNKNOWN_DEVICE", 3))
    }

    @Test
    fun nothingInNothingOut() {
        assertTrue(InboxRows.rows(emptyList(), null).isEmpty())
    }
}
