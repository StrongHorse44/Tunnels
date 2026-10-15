package io.github.stronghorse44.tunnels.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingUrisTest {
    private val release = "io.github.stronghorse44.tunnels"
    private val debug = "io.github.stronghorse44.tunnels.debug"

    @Test
    fun incomingUriAcceptsOnlyContentScheme() {
        assertTrue(IncomingUris.accepts("content", "com.android.providers.downloads.documents", release))
        assertTrue(IncomingUris.accepts("CONTENT", "media", release))
        assertFalse(IncomingUris.accepts("file", null, release))
        assertFalse(IncomingUris.accepts("file", "", release))
        assertFalse(IncomingUris.accepts("http", "example.com", release))
        assertFalse(IncomingUris.accepts(null, "media", release))
        assertFalse(IncomingUris.accepts("content", null, release))
        assertFalse(IncomingUris.accepts("content", "", release))
    }

    @Test
    fun incomingUriRefusesTunnelsOwnAuthorities() {
        assertFalse(IncomingUris.accepts("content", "$release.breaches", release))
        assertFalse(IncomingUris.accepts("content", "$release.shizuku", release))
        assertFalse(IncomingUris.accepts("content", release, release))
        assertFalse(IncomingUris.accepts("content", "IO.GITHUB.STRONGHORSE44.TUNNELS.BREACHES", release))
        assertFalse(IncomingUris.accepts("content", "user@$release.breaches:80", release))
        // The debug package's own providers too, derived from its package name.
        assertFalse(IncomingUris.accepts("content", "$debug.breaches", debug))
        // A different app whose name merely begins the same way is not Tunnels.
        assertTrue(IncomingUris.accepts("content", "io.github.stronghorse44.tunnelsx.files", release))
    }
}
