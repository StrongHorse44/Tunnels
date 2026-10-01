package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficKeysTest {
    @Test
    fun systemUidLabels() {
        assertEquals("System DNS resolver (netd)", TrafficKeys.systemUidLabel("uid:1051"))
        assertEquals("GPS / PSDS (satellite data)", TrafficKeys.systemUidLabel("uid:1021"))
        assertNull(TrafficKeys.systemUidLabel("uid:10234"))
        assertNull(TrafficKeys.systemUidLabel("com.example"))
        assertNull(TrafficKeys.systemUidLabel("uid:x"))
    }
}
