package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeviceNotesTest {
    private val net = NetworkFingerprint.sha256Hex("net:home")
    private fun tok(n: Int, type: Char = 'n') = type + "%016x".format(n)
    private val day = LocalDate.of(2026, 10, 11).toEpochDay()

    @Test
    fun aNameGivenWhenAskedDirectlyMakesAnIdentityLast() {
        val quiet = LanHostRecord("192.168.1.50")
        assertTrue(DeviceIdentity.tokens(net, quiet, HostKind.UNKNOWN, null).isEmpty())
        quiet.directNames += listOf("LENOVO-T14", "lenovo-t14 ")
        val ids = DeviceIdentity.tokens(net, quiet, HostKind.UNKNOWN, null)
        assertEquals(1, ids.size)
        assertTrue(ids[0].startsWith("c") && DeviceIdentity.isToken(ids[0]))
        // After every other kind, so a device already on the list keeps its primary token.
        val named = LanHostRecord("192.168.1.51").apply { names += "Living Room TV"; routerName = "tv"; directNames += "TV-PC" }
        assertEquals(listOf('n', 'h', 'c'), DeviceIdentity.tokens(net, named, HostKind.TV, null).map { it[0] })
        assertTrue(DeviceKeys.isKey(ids[0]))
    }

    @Test
    fun keysFollowTheDeviceAndAddressNotesMoveOnlyToQuietDevices() {
        assertEquals("ip:192.168.1.50", DeviceKeys.Host(emptyList(), "192.168.1.50").key)
        assertEquals(tok(1, 'u'), DeviceKeys.Host(listOf(tok(1, 'u'), tok(2)), "192.168.1.5").key)
        assertTrue(DeviceKeys.isKey("ip:fe80::1") && DeviceKeys.isKey(tok(9, 'c')))
        assertFalse(DeviceKeys.isKey("ip:") || DeviceKeys.isKey("x" + tok(1).drop(1)) || DeviceKeys.isKey("ip:1.2.3.4;x"))

        val notes = mapOf("ip:192.168.1.50" to "the laptop", tok(2) to "tv", "ip:192.168.1.60" to "someone's")
        val moved = DeviceKeys.rekey(
            notes,
            listOf(
                // Became recognisable after a look closer: its address note follows it.
                DeviceKeys.Host(listOf(tok(7, 'c')), "192.168.1.50"),
                // The SSDP reply came back, so the primary token is another one: the note moves to it.
                DeviceKeys.Host(listOf(tok(1, 'u'), tok(2)), "192.168.1.5"),
                // A device that announces itself never takes what was noted under its address.
                DeviceKeys.Host(listOf(tok(3)), "192.168.1.60"),
            ),
        )
        assertEquals(mapOf(tok(7, 'c') to "the laptop", tok(1, 'u') to "tv", "ip:192.168.1.60" to "someone's"), moved)
        // A key that already holds a note keeps it.
        assertEquals(mapOf(tok(7, 'c') to "a", "ip:192.168.1.50" to "b"), DeviceKeys.rekey(mapOf(tok(7, 'c') to "a", "ip:192.168.1.50" to "b"), listOf(DeviceKeys.Host(listOf(tok(7, 'c')), "192.168.1.50"))))
    }

    @Test
    fun presenceCountsDaysAndScansWithoutATimeline() {
        var book = Presence.Book.EMPTY
        book = Presence.update(book, day, listOf("ip:10.0.0.1", "ip:10.0.0.2"))
        book = Presence.update(book, day, listOf("ip:10.0.0.1"))
        assertEquals(1, book.scanDays)
        val first = book.entries.getValue("ip:10.0.0.1")
        assertEquals(Presence.Entry(day, day, 2, 1, 1), first)
        // First day of the network: nothing is "new", everything was seen once.
        assertEquals(Presence.Pattern.ONCE, Presence.pattern(first, book, day))

        for (d in 1..4) book = Presence.update(book, day + d, listOf("ip:10.0.0.1") + if (d == 4) listOf("ip:10.0.0.3") else emptyList())
        val always = book.entries.getValue("ip:10.0.0.1")
        assertEquals(5, always.days)
        assertEquals(6, always.scans)
        assertEquals(Presence.Pattern.ALWAYS, Presence.pattern(always, book, day + 4))
        assertEquals("Here on 5 of 5 days scanned, 6 scans, since 2026-10-11", Presence.words(always, book))
        assertEquals(Presence.Pattern.NEW, Presence.pattern(book.entries.getValue("ip:10.0.0.3"), book, day + 4))
        // Seen on one day of five.
        assertEquals(Presence.Pattern.ONCE, Presence.pattern(book.entries.getValue("ip:10.0.0.2"), book, day + 4))
        assertEquals(book, Presence.Book.parse(book.encode()))

        // Not seen for more than 30 days: forgotten (rule 4). The cap keeps the most recently seen.
        val later = Presence.update(book, day + 40, listOf("ip:10.0.0.9"))
        assertEquals(setOf("ip:10.0.0.9"), later.entries.keys)
        val many = Presence.update(Presence.Book.EMPTY, day, (1..200).map { "ip:10.0.1.$it" })
        assertEquals(Presence.MAX_ENTRIES, many.entries.size)

        assertEquals(Presence.Book.EMPTY, Presence.Book.parse("v2;1;2"))
        assertEquals(emptyMap<String, Presence.Entry>(), Presence.Book.parse("v1;1;2;bad key=1,2,3,4,5;ip:1.2.3.4=1,2,3").entries)
    }

    @Test
    fun aDeviceThatComesAndGoesReadsSo() {
        var book = Presence.Book.EMPTY
        for (d in 0..9) book = Presence.update(book, day + d, listOf("ip:10.0.0.1") + if (d % 4 == 0) listOf("ip:10.0.0.2") else emptyList())
        val visitor = book.entries.getValue("ip:10.0.0.2")
        assertEquals(3, visitor.days)
        assertEquals(10, Presence.possibleDays(visitor, book))
        assertEquals(Presence.Pattern.SOMETIMES, Presence.pattern(visitor, book, day + 9))
        for (d in 10..13) book = Presence.update(book, day + d, listOf("ip:10.0.0.2"))
        // 7 of 14 days: half of them.
        assertEquals(Presence.Pattern.OFTEN, Presence.pattern(book.entries.getValue("ip:10.0.0.2"), book, day + 13))
    }

    @Test
    fun labelsAreCleanCappedAndRoundTrip() {
        var labels = Labels.with(emptyMap(), "ip:192.168.1.50", "  Laptop;  in the=office, 100%\n")!!
        assertEquals("Laptop; in the=office, 100%", labels["ip:192.168.1.50"])
        labels = Labels.with(labels, tok(1), "x".repeat(100))!!
        assertEquals(Labels.MAX_LENGTH, labels.getValue(tok(1)).length)
        assertEquals(labels, Labels.parse(Labels.encode(labels)))
        assertEquals(setOf(tok(1)), Labels.with(labels, "ip:192.168.1.50", "   ")!!.keys)
        val full = (1..Labels.MAX_LABELS).associate { tok(it) to "d$it" }
        assertNull(Labels.with(full, tok(999), "one more"))
        assertEquals("renamed", Labels.with(full, tok(1), "renamed")!![tok(1)])
        assertEquals(emptyMap<String, String>(), Labels.parse("not a key=x;ip:1.2.3.4=%zz"))
    }

    @Test
    fun watchSaysBackAndWhatChangedButNotGone() {
        val tag = "ab12cd34"
        val now = Watch.Now(listOf(80, 554), "Garage cam")
        var states = Watch.with(emptyList(), tag, "ip:192.168.1.50", watch = true, now = now)!!
        assertTrue(Watch.isWatched(states, tag, "ip:192.168.1.50"))
        assertFalse(Watch.isWatched(states, "ffffffff", "ip:192.168.1.50"))
        assertEquals(states, Watch.parse(Watch.encode(states)))

        // Gone: no news, kept as absent.
        var out = Watch.afterScan(states, tag, emptyMap(), portsComplete = true)
        assertTrue(out.events.isEmpty())
        assertFalse(out.states.single().present)
        // Back.
        out = Watch.afterScan(out.states, tag, mapOf("ip:192.168.1.50" to now), portsComplete = true)
        assertEquals(Watch.Event.Back, out.events["ip:192.168.1.50"])
        // Changed: a port opened, one closed, a new name.
        out = Watch.afterScan(out.states, tag, mapOf("ip:192.168.1.50" to Watch.Now(listOf(80, 23), "IPC")), portsComplete = true)
        val changed = out.events.getValue("ip:192.168.1.50") as Watch.Event.Changed
        assertEquals(
            listOf("opened Telnet remote login (23)", "closed Video stream (RTSP) (554)", "now calls itself \"IPC\" (was \"Garage cam\")"),
            changed.what,
        )
        // A port stage cut short compares no ports and keeps the old ones.
        out = Watch.afterScan(out.states, tag, mapOf("ip:192.168.1.50" to Watch.Now(emptyList(), "IPC")), portsComplete = false)
        assertTrue(out.events.isEmpty())
        assertEquals(listOf(23, 80), out.states.single().ports)
        // Other networks' entries are untouched.
        val other = Watch.State("ffffffff", "ip:192.168.1.50", present = true, ports = listOf(22), name = null)
        assertEquals(other, Watch.afterScan(listOf(other), tag, emptyMap(), true).states.single())

        states = Watch.with(out.states + other, tag, "ip:192.168.1.50", watch = false, now = now)!!
        assertEquals(listOf(other), states)
        assertEquals(emptyList<Watch.State>(), Watch.without(states + Watch.State(tag, "ip:192.168.1.50", true, emptyList(), null), "ip:192.168.1.50"))
        val full = (1..Watch.MAX_WATCHED).map { Watch.State(tag, tok(it), true, emptyList(), null) }
        assertNull(Watch.with(full, tag, tok(999), watch = true, now = now))
        assertEquals(emptyList<Watch.State>(), Watch.parse("nottag,ip:1.2.3.4,1,,;ab12cd34,bad,1,,"))
    }

    @Test
    fun watchNewsRoundTripsAndBecomesADatedFinding() {
        val changed = Watch.Event.Changed(listOf("opened Telnet (23)", "now calls itself \"a,b;c\" (was \"x\")"))
        val value = Watch.observation(day, changed)
        assertEquals(Watch.Noted(day, changed), Watch.parseObservation(value))
        assertEquals(Watch.Noted(day, Watch.Event.Back), Watch.parseObservation(Watch.observation(day, Watch.Event.Back)))
        assertNull(Watch.parseObservation("back"))
        assertNull(Watch.parseObservation("$day;changed:"))
        assertNull(Watch.parseObservation("$day;gone"))

        fun o(subject: String, key: String, value: String) = Observation(LanKeys.TUNNEL_ID, subject, key, value)
        val current = listOf(
            o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED),
            o("192.168.1.50", LanKeys.HOST_KIND, HostKind.UNKNOWN.label),
            o("192.168.1.50", LanKeys.HOST_LABEL, "Garage cam"),
            o("192.168.1.50", LanKeys.HOST_WATCH_EVENT, Watch.observation(day, Watch.Event.Back)),
            o("192.168.1.60", LanKeys.HOST_KIND, HostKind.TV.label),
            o("192.168.1.60", LanKeys.HOST_IDS, tok(4)),
            o("192.168.1.60", LanKeys.HOST_NAME, "Bravia"),
            o("192.168.1.60", LanKeys.HOST_WATCH_EVENT, value),
        )
        val drafts = LanRules.watchedDevice.evaluate(RuleContext(LanKeys.TUNNEL_ID, current, emptyList(), isFirstScan = false))
        assertEquals(listOf(LanRules.WATCHED_BACK, LanRules.WATCHED_CHANGED), drafts.map { it.kind })
        assertEquals("Garage cam · ip:192.168.1.50 · 2026-10-11", drafts[0].subject)
        assertEquals("Bravia · ${tok(4)} · 2026-10-11", drafts[1].subject)
        assertTrue(drafts.all { it.sticky && it.severity == Severity.NOTICE })
        assertEquals(
            "Bravia, a device you watch, changed since the last scan: opened Telnet (23); now calls itself \"a,b;c\" (was \"x\"). It is at 192.168.1.60.",
            drafts[1].evidence,
        )
        assertEquals("ip:192.168.1.50", LanRules.watchKeyOf(drafts[0].subject))
        assertEquals(tok(4), LanRules.watchKeyOf(drafts[1].subject))
        assertNull(LanRules.watchKeyOf("Bravia · ${tok(4)}"))
        // Nothing is judged on a scan the gate did not confirm.
        val unconfirmed = current.filterNot { it.key == LanKeys.SCAN_GATE } + o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_UNCONFIRMED)
        assertTrue(LanRules.watchedDevice.evaluate(RuleContext(LanKeys.TUNNEL_ID, unconfirmed, emptyList(), isFirstScan = false)).isEmpty())
        // The label titles the device on the screen too.
        val host = LanSummary.from(current).hosts.first { it.ip == "192.168.1.50" }
        assertEquals("Garage cam", host.title)
        assertEquals("ip:192.168.1.50", host.noteKey)
        assertTrue(LanGuides.wantsRouterAdmin(LanRules.WATCHED_BACK))
        assertFalse(LanGuides.wantsRouterAdmin(LanRules.WATCHED_CHANGED))
    }
}
