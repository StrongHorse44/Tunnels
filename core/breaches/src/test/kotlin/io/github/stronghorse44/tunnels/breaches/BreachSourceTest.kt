package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreachSourceTest {
    @Test
    fun onlyHttpsAndTheOneHost() {
        assertTrue(BreachSource.isAllowed("https", "haveibeenpwned.com"))
        assertTrue(BreachSource.isAllowed("HTTPS", "HaveIBeenPwned.com"))
        assertFalse(BreachSource.isAllowed("http", "haveibeenpwned.com"))
        assertFalse(BreachSource.isAllowed("ftp", "haveibeenpwned.com"))
        assertFalse(BreachSource.isAllowed("", "haveibeenpwned.com"))
        assertEquals("https://haveibeenpwned.com/api/v3/breaches", BreachSource.URL)
        assertTrue(BreachSource.URL.startsWith("https://${BreachSource.HOST}/"))
    }

    @Test
    fun lookalikeHostRefused() {
        for (host in listOf(
            "haveibeenpwned.com.evil.example", "haveibeenpwned.co", "haveibeenpwned.org", "haveibeenpwnd.com",
            "have-i-been-pwned.com", "haveibeenpwned.com:443", "haveibeenpwned.com.", "xn--haveibeenpwned.com", "",
            "haveibeenpwned.com@evil.example", "haveibeenpwned.com\u0000.evil.example",
        )) assertFalse(host, BreachSource.isAllowed("https", host))
    }

    @Test
    fun unicodeLookalikesRefused() {
        // Dotless i (U+0131), long s (U+017F) and the Kelvin sign (U+212A) fold onto ASCII letters in some case mappings.
        assertFalse(BreachSource.isAllowed("https", "have\u0131beenpwned.com"))
        assertFalse(BreachSource.isAllowed("https", "HAVE\u0130BEENPWNED.COM"))
        assertFalse(BreachSource.isAllowed("http\u017F", "haveibeenpwned.com"))
        assertFalse(BreachSource.isAllowed("https", "haveibeenpwned.com\u212A"))
        assertFalse(BreachSource.isAllowed("https", "\uFF48aveibeenpwned.com")) // full-width h
        assertTrue(BreachSource.isAllowed("HtTpS", "HAVEIBEENPWNED.COM"))
    }

    @Test
    fun suffixHostRefused() {
        for (host in listOf("api.haveibeenpwned.com", "www.haveibeenpwned.com", "evilhaveibeenpwned.com", ".haveibeenpwned.com", "com")) {
            assertFalse(host, BreachSource.isAllowed("https", host))
        }
    }
}
