package io.github.stronghorse44.tunnels.crossrules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourcesTest {
    @Test
    fun classifiesInstallers() {
        assertEquals(InstallSource.STORE, InstallSources.classify("com.android.vending", false))
        assertEquals(InstallSource.STORE, InstallSources.classify("app.accrescent.client", false))
        assertEquals(InstallSource.FILE, InstallSources.classify("com.android.packageinstaller", false))
        assertEquals(InstallSource.FILE, InstallSources.classify("io.github.stronghorse44.tunnels.debug", false))
        assertEquals(InstallSource.UNKNOWN, InstallSources.classify("unknown", false))
        assertEquals(InstallSource.UNKNOWN, InstallSources.classify(null, false))
        assertEquals(InstallSource.OTHER, InstallSources.classify("dev.imranr.obtainium", false))
        assertEquals(InstallSource.SYSTEM, InstallSources.classify(null, true))
    }

    @Test
    fun outsideStoreIsFileUnknownOrOther() {
        assertEquals(setOf(InstallSource.FILE, InstallSource.UNKNOWN, InstallSource.OTHER), InstallSource.entries.filter { it.outsideStore }.toSet())
        assertTrue(InstallSource.of("file")!!.outsideStore)
        assertFalse(InstallSource.of("store")!!.outsideStore)
        assertEquals(null, InstallSource.of("nonsense"))
    }

    @Test
    fun namesKnownInstallers() {
        assertEquals("Obtainium", InstallSources.nameOf("dev.imranr.obtainium"))
        assertEquals("Google Play", InstallSources.nameOf("com.android.vending"))
        assertEquals("org.example.market", InstallSources.nameOf("org.example.market"))
        assertEquals("was installed by org.example.market, not by a store", InstallSources.describe(InstallSource.OTHER, "org.example.market"))
    }
}
