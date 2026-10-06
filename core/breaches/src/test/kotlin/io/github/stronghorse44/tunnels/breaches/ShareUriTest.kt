package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareUriTest {
    private val auth = "io.github.stronghorse44.tunnels.breaches"
    private val token = "0123456789abcdef0123456789abcdef"

    private fun tokenOf(
        scheme: String? = "content",
        authority: String? = auth,
        segments: List<String> = listOf("catalogue", token),
        query: Boolean = false,
    ) = ShareUri.tokenOf(auth, scheme, authority, segments, query)

    @Test
    fun exactlyTheOneAddressIsServed() {
        assertEquals(token, tokenOf())
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(tokenOf(scheme = "file"))
        assertNull(tokenOf(scheme = null))
        assertNull(tokenOf(authority = "io.github.stronghorse44.tunnels.other"))
        assertNull(tokenOf(authority = null))
        assertNull(tokenOf(query = true))
        assertNull(tokenOf(segments = emptyList()))
        assertNull(tokenOf(segments = listOf("catalogue")))
        assertNull(tokenOf(segments = listOf("catalogue", token, "more")))
        assertNull(tokenOf(segments = listOf("other", token)))
        assertNull(tokenOf(segments = listOf("catalogue", token.uppercase())))
        assertNull(tokenOf(segments = listOf("catalogue", token.take(31))))
        assertNull(tokenOf(segments = listOf("catalogue", "..")))
        assertNull(tokenOf(segments = listOf("catalogue", "$token/..")))
    }

    @Test
    fun theReleaseAuthorityIsTheFrozenStringAndDebugFollowsItsPackage() {
        assertEquals("io.github.stronghorse44.tunnels.breaches", ShareUri.authorityFor("io.github.stronghorse44.tunnels"))
        assertEquals("io.github.stronghorse44.tunnels.debug.breaches", ShareUri.authorityFor("io.github.stronghorse44.tunnels.debug"))
    }

    @Test
    fun displayNameIsTheFetchDate() {
        assertEquals("breaches-20261005.txt", ShareUri.displayName("2026-10-05T23:00:00Z"))
    }
}
