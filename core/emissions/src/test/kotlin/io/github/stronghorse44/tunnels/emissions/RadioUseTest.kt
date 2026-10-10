package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioUseTest {
    private val dump = """
        Current AppOps Service state:
          Uid 0:
            state=pers
          Uid u0a120:
            state=top
            Package com.example.scanner:
              BLUETOOTH_SCAN (allow):
                null=[
                  Access: [top-s] 2026-10-10 12:00:00.000 (-2m10s5ms) duration=+3s
                  Access: [bg-s] 2026-10-10 09:00:00.000 (-3h1m)
                ]
          Uid u0a121:
            state=cch
            Package com.example.old:
              BLUETOOTH_SCAN (allow):
                  Access: [cch-s] 2026-10-09 12:00:00.000 (-1d2h)
          Uid u0a122:
            Package com.example.beacon:
              BLUETOOTH_SCAN (allow):
                  Running start at: +5m3s ago
          Uid u0a123:
            Package io.github.stronghorse44.tunnels:
              BLUETOOTH_SCAN (allow):
                  Access: [top-s] 2026-10-10 12:01:00.000 (-30s)
    """.trimIndent()

    @Test
    fun parsesTheNewestAccessPerPackageAndRunningOps() {
        val uses = RadioUse.parse("BLUETOOTH_SCAN", dump).associateBy { it.packageName }
        assertEquals(130_005L, uses.getValue("com.example.scanner").agoMillis)
        assertEquals(93_600_000L, uses.getValue("com.example.old").agoMillis)
        assertTrue(uses.getValue("com.example.beacon").running)
        assertNull(uses.getValue("com.example.beacon").agoMillis)
    }

    @Test
    fun recentKeepsTheLastFifteenMinutesWithoutTunnelsItself() {
        val uses = RadioUse.parse("BLUETOOTH_SCAN", dump) + RadioUse.parse("WIFI_SCAN", "Package com.example.scanner:\n  Access: [fg-s] d t (-20m)")
        val recent = RadioUse.recent(uses, self = "io.github.stronghorse44.tunnels")
        assertEquals(listOf("com.example.beacon", "com.example.scanner"), recent.getValue(RadioUse.Kind.BLUETOOTH).map { it.packageName })
        assertNull(recent[RadioUse.Kind.WIFI])
        val counts = RadioUse.counts(recent)
        assertEquals(2, counts.counts[RadioUse.Kind.BLUETOOTH])
        assertEquals(0, counts.counts[RadioUse.Kind.WIFI])
        assertEquals("now", RadioUse.ago(recent.getValue(RadioUse.Kind.BLUETOOTH).first()))
        assertEquals("2 min ago", RadioUse.ago(recent.getValue(RadioUse.Kind.BLUETOOTH)[1]))
    }

    @Test
    fun oneEntryPerAppPerKind() {
        val uses = listOf(
            RadioUse.Use("a.b", "BLUETOOTH_SCAN", 600_000L, false),
            RadioUse.Use("a.b", "BLUETOOTH_CONNECT", 60_000L, false),
        )
        assertEquals(listOf("BLUETOOTH_CONNECT"), RadioUse.recent(uses).getValue(RadioUse.Kind.BLUETOOTH).map { it.op })
    }

    @Test
    fun durations() {
        assertEquals(0L, RadioUse.parseDuration("0"))
        assertEquals(7_384_005L, RadioUse.parseDuration("+2h3m4s5ms"))
        assertEquals(60_000L, RadioUse.parseDuration("-1m"))
        assertNull(RadioUse.parseDuration("soon"))
        assertNull(RadioUse.parseDuration("2x"))
    }

    @Test
    fun everyOpBelongsToOneKind() {
        val ops = RadioUse.Kind.entries.flatMap { it.ops }
        assertEquals(ops.size, ops.toSet().size)
        ops.forEach { assertTrue(it, RadioUse.Kind.ofOp(it) != null) }
    }
}
