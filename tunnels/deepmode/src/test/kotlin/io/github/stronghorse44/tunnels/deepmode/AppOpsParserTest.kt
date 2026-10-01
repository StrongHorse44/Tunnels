package io.github.stronghorse44.tunnels.deepmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsParserTest {
    private val sample = """
        Uid mode: COARSE_LOCATION: foreground
        CAMERA: allow; time=+2h13m40s503ms ago; duration=+1s500ms
        RECORD_AUDIO: allow; time=+3d1h2m3s4ms ago; rejectTime=+5d2h ago
        COARSE_LOCATION: foreground; time=+21h50m31s868ms ago
        FINE_LOCATION: ignore
        READ_CLIPBOARD: allow; time=+45d ago
        WRITE_CLIPBOARD: allow; time=+1m ago (running)
        WRITE_SETTINGS: default; time=+10s ago
        VIBRATE: allow; time=+500ms ago
        GET_USAGE_STATS: deny
    """.trimIndent()

    @Test
    fun parsesModesAndAges() {
        val ops = AppOpsParser.parseGet(sample)
        assertEquals("allow", ops.getValue("CAMERA").mode)
        assertEquals(2 * 3_600_000L + 13 * 60_000L + 40_000L + 503L, ops.getValue("CAMERA").lastAccessAgo)
        assertEquals("allow", ops.getValue("RECORD_AUDIO").mode)
        assertEquals(3 * 86_400_000L + 3_600_000L + 2 * 60_000L + 3_000L + 4L, ops.getValue("RECORD_AUDIO").lastAccessAgo)
        assertEquals(5 * 86_400_000L + 2 * 3_600_000L, ops.getValue("RECORD_AUDIO").lastRejectAgo)
        assertEquals("foreground", ops.getValue("COARSE_LOCATION").mode)
        assertEquals("ignore", ops.getValue("FINE_LOCATION").mode)
        assertNull(ops.getValue("FINE_LOCATION").lastAccessAgo)
        assertTrue(ops.getValue("WRITE_CLIPBOARD").running)
        assertEquals("deny", ops.getValue("GET_USAGE_STATS").mode)
        assertFalse("uid mode lines are not ops of their own", ops.containsKey("Uid mode"))
        assertEquals("foreground", ops.getValue("COARSE_LOCATION").mode)
        assertEquals("the uid mode agreed with the package line", "foreground", ops.getValue("COARSE_LOCATION").packageMode)
        assertNull(ops.getValue("CAMERA").packageMode)
        assertEquals(9, ops.size)
    }

    /**
     * Shape of `appops get --user 0 <pkg>` on Android 14/15 for an app whose camera permission is denied
     * and whose location is "while in use": the runtime permission lives on the `Uid mode:` line, the
     * package line keeps its default/allow mode and the access time.
     */
    @Test
    fun uidModeOverridesThePackageMode() {
        val text = """
            Uid mode: CAMERA: ignore
            Uid mode: RECORD_AUDIO: ignore
            Uid mode: COARSE_LOCATION: foreground
            Uid mode: FINE_LOCATION: foreground
            Uid mode: READ_CONTACTS: allow
            CAMERA: allow; time=+2h ago
            RECORD_AUDIO: default; time=+3d ago; rejectTime=+1h ago
            FINE_LOCATION: allow; time=+30m ago; duration=+2s
            READ_CLIPBOARD: allow; time=+1d ago
        """.trimIndent()
        val ops = AppOpsParser.parseGet(text)
        val camera = ops.getValue("CAMERA")
        assertEquals("ignore", camera.mode)
        assertEquals("allow", camera.packageMode)
        assertEquals(2 * 3_600_000L, camera.lastAccessAgo)
        val mic = ops.getValue("RECORD_AUDIO")
        assertEquals("ignore", mic.mode)
        assertEquals(3 * 86_400_000L, mic.lastAccessAgo)
        assertEquals(3_600_000L, mic.lastRejectAgo)
        assertEquals("foreground", ops.getValue("FINE_LOCATION").mode)
        assertEquals(30 * 60_000L, ops.getValue("FINE_LOCATION").lastAccessAgo)
        // Only a uid-mode line: the op is known with its mode but has never been used.
        val coarse = ops.getValue("COARSE_LOCATION")
        assertEquals("foreground", coarse.mode)
        assertNull(coarse.lastAccessAgo)
        assertNull(coarse.packageMode)
        assertEquals("allow", ops.getValue("READ_CONTACTS").mode)
        // No uid line: the package line stands.
        assertEquals("allow", ops.getValue("READ_CLIPBOARD").mode)
        assertNull(ops.getValue("READ_CLIPBOARD").packageMode)
        assertEquals(6, ops.size)
        // The filter applies to uid-only ops too.
        assertEquals(setOf("CAMERA", "RECORD_AUDIO"), AppOpsParser.parseGet(text, listOf("CAMERA", "RECORD_AUDIO")).keys)
    }

    @Test
    fun filtersToTrackedOps() {
        val ops = AppOpsParser.parseGet(sample, DeepKeys.TRACKED_OPS)
        assertEquals(setOf("CAMERA", "RECORD_AUDIO", "COARSE_LOCATION", "FINE_LOCATION", "READ_CLIPBOARD", "WRITE_CLIPBOARD", "GET_USAGE_STATS"), ops.keys)
    }

    @Test
    fun ignoresNoOperationsAndErrors() {
        assertTrue(AppOpsParser.parseGet("No operations.").isEmpty())
        assertTrue(AppOpsParser.parseGet("Error: Unknown package: com.missing").isEmpty())
        assertTrue(AppOpsParser.parseGet("").isEmpty())
    }

    @Test
    fun splitsBatchOutputByMarker() {
        val text = """
            ## com.a
            CAMERA: allow; time=+1h ago
            ## com.b
            No operations.
            ## com.c
            RECORD_AUDIO: ignore
            VIBRATE: allow
        """.trimIndent()
        val parts = AppOpsParser.splitBatch(text)
        assertEquals(listOf("com.a", "com.b", "com.c"), parts.keys.toList())
        assertEquals("CAMERA", AppOpsParser.parseGet(parts.getValue("com.a")).keys.single())
        assertTrue(AppOpsParser.parseGet(parts.getValue("com.b")).isEmpty())
        assertEquals(2, AppOpsParser.parseGet(parts.getValue("com.c")).size)
    }

    @Test
    fun parsesDurations() {
        assertEquals(0L, AppOpsParser.parseDuration("0"))
        assertEquals(1_500L, AppOpsParser.parseDuration("+1s500ms"))
        assertEquals(86_400_000L * 2 + 3_600_000L * 3, AppOpsParser.parseDuration("-2d3h"))
        assertEquals(60_000L, AppOpsParser.parseDuration("1m"))
        assertNull(AppOpsParser.parseDuration("soon"))
        assertNull(AppOpsParser.parseDuration("+1x"))
        assertNull(AppOpsParser.parseDuration(""))
    }

    @Test
    fun readsBackgroundAccessesFromDumpsys() {
        val text = """
            Current AppOps Service state:
              Settings:
                top_state_settle_time=+5s0ms
              Uid u0a123:
                state=cch
                Package com.example.tracker:
                  CAMERA (allow):
                    null=[
                      Access: [fg-s] 2025-09-30 12:00:00.000 (-2h3m) duration=+1s
                      Access: [bg-s] 2025-09-28 03:10:00.000 (-2d10h) duration=+3s
                      Access: [cch-s] 2025-09-20 03:10:00.000 (-10d) duration=+3s
                      Reject: [bg-s] 2025-09-29 03:10:00.000 (-1d)
                    ]
              Uid u0a200:
                state=top
                Package com.example.camera:
                  CAMERA (allow):
                    null=[
                      Access: [top-s] 2025-09-30 13:00:00.000 (-1h) duration=+20s
                    ]
                Package com.example.bgonly:
                  CAMERA (allow):
                    null=[
                      Access: [bg-tp] 2025-09-30 13:00:00.000 (-6d) duration=+20s
                    ]
        """.trimIndent()
        val bg = AppOpsParser.parseDumpsysBackground(text)
        assertEquals(2 * 86_400_000L + 10 * 3_600_000L, bg["com.example.tracker"])
        assertFalse("foreground-only history is not background", bg.containsKey("com.example.camera"))
        assertEquals(6 * 86_400_000L, bg["com.example.bgonly"])
        assertEquals(setOf("CAMERA"), AppOpsParser.dumpsysOps(text))
    }
}
