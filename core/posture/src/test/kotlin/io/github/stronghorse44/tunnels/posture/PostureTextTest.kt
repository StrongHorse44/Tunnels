package io.github.stronghorse44.tunnels.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureTextTest {
    private val reboot = PostureKeys.item(PostureKeys.AUTO_REBOOT)!!
    private val wifi = PostureKeys.item(PostureKeys.WIFI_AUTO_OFF)!!

    @Test
    fun stateWordsAreTheSpecs() {
        assertEquals(listOf("ok", "weak", "unknown", "n/a"), PostureState.entries.map { PostureText.stateWord(it) })
    }

    @Test
    fun detailLinesPerState() {
        assertEquals("12 h", PostureText.detail(Reading(reboot, PostureState.GOOD, "12 h", null)))
        assertEquals("off", PostureText.detail(Reading(reboot, PostureState.WEAK, "off", null)))
        val dns = PostureKeys.item(PostureKeys.PRIVATE_DNS)!!
        assertEquals(
            "Not set on this phone, so Tunnels can't tell (GrapheneOS's built-in default is automatic). Change it once in Settings, then rescan.",
            PostureText.detail(Reading(dns, PostureState.UNKNOWN, null, PostureWhy.ABSENT)),
        )
        // The three timers were not found in Settings on this build: no instruction to change them.
        for (id in listOf(PostureKeys.WIFI_AUTO_OFF, PostureKeys.BT_AUTO_OFF, PostureKeys.NFC_AUTO_OFF)) {
            assertEquals(
                "Not set on this phone and not found in Settings on this build; GrapheneOS's built-in default is never.",
                PostureText.detail(Reading(PostureKeys.item(id)!!, PostureState.UNKNOWN, null, PostureWhy.ABSENT)),
            )
        }
        assertEquals("Could not be read this scan.", PostureText.detail(Reading(wifi, PostureState.UNKNOWN, null, PostureWhy.UNREADABLE)))
        assertEquals("Unexpected value; not judged.", PostureText.detail(Reading(wifi, PostureState.UNKNOWN, null, PostureWhy.MALFORMED)))
        assertEquals(
            "Reads off; the key is not confirmed on this phone yet.",
            PostureText.detail(Reading(wifi, PostureState.UNKNOWN, "off", PostureWhy.UNCONFIRMED)),
        )
        assertEquals("No always-on VPN is set.", PostureText.detail(Reading(PostureKeys.item(PostureKeys.VPN_ALWAYS_ON)!!, PostureState.NA, "none", null)))
        assertTrue(PostureText.detail(Reading(PostureKeys.item(PostureKeys.PIN_SCRAMBLE_2)!!, PostureState.NA, "on", null)).startsWith("on."))
    }

    @Test
    fun theCardsSentences() {
        assertEquals(
            "Posture needs Shizuku: Shizuku is installed but not running. Nothing has been read.",
            PostureText.needsShizuku("Shizuku is installed but not running"),
        )
        assertEquals("Scan Deep mode to read posture.", PostureText.NOT_SCANNED)
        assertEquals(
            "No app can read whether a duress PIN is set. Check it yourself: Security & privacy > Device unlock > Duress password.",
            PostureText.DURESS_NOTE,
        )
    }

    @Test
    fun everyUnknownReasonHasALine() {
        for (why in PostureWhy.entries) assertTrue(PostureText.detail(Reading(wifi, PostureState.UNKNOWN, null, why)).isNotBlank())
    }
}
