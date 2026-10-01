package io.github.stronghorse44.tunnels.silicon

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun osVersion() {
        assertEquals("16.0.0", formatOsVersion("160000"))
        assertEquals("15.1.2", formatOsVersion("150102"))
        assertEquals("14", formatOsVersion("14"))
        assertEquals("abc", formatOsVersion("abc"))
    }
}
