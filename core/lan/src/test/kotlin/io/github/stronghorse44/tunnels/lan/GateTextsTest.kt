package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The spec's sentences (2.5, 2.6), word for word, and which refusal gets which. */
class GateTextsTest {
    @Test
    fun theCardSentencesAreThoseOfTheSpec() {
        assertEquals("Tunnels now also checks your router's identity before it scans. Confirm this network once more.", GateTexts.refusal("reconfirm"))
        assertEquals(
            "This network has your network's addresses, but its router identified itself differently, so Tunnels sends nothing more. " +
                "If you replaced your router, confirm it again.",
            GateTexts.refusal("router-mismatch"),
        )
        assertEquals("Your router didn't identify itself this time, so Tunnels sends nothing more. Try again in a moment.", GateTexts.refusal("router-silent"))
        assertEquals("Tunnels' key for confirmed networks changed, so it can't recognise them. Confirm this network again.", GateTexts.refusal("key-changed"))
        assertEquals(
            "Recognised by address only. Is this your own Wi-Fi? Tunnels can't tell it from another with the same addresses.",
            GateTexts.refusal("address-only"),
        )
        assertEquals("This Wi-Fi is not confirmed as yours, so Tunnels sends nothing.", GateTexts.refusal("not-confirmed"))
    }

    @Test
    fun closeItsSentencesAreThoseOfTheSpec() {
        assertEquals("This isn't the router you confirmed, so Tunnels sends nothing.", GateTexts.closeRefusal("router-mismatch"))
        assertEquals(
            "Your router didn't identify itself just now, so Tunnels sends nothing. Try again, or remove it on the router's admin page.",
            GateTexts.closeRefusal("router-silent"),
        )
        val noUpnp = "Tunnels can't confirm this router's UPnP identity, so it doesn't ask it to change anything. Remove it on the router's admin page."
        assertEquals(noUpnp, GateTexts.closeRefusal("router-no-upnp"))
        assertEquals(noUpnp, GateTexts.closeRefusal("address-only"))
        assertEquals("This Wi-Fi is not confirmed as yours, so Tunnels sends nothing.", GateTexts.closeRefusal("not-confirmed"))
        assertEquals("This Wi-Fi is not confirmed as yours, so Tunnels sends nothing.", GateTexts.closeRefusal(null))
    }

    @Test
    fun theConfirmationSaysWhichFactorsTheRouterGave() {
        assertEquals("Confirmed as your network. Your router identified itself (UPnP id · admin page key).", GateTexts.confirmed(true, true))
        assertEquals("Confirmed as your network. Your router identified itself (UPnP id).", GateTexts.confirmed(true, false))
        assertEquals("Confirmed as your network. Your router identified itself (admin page key).", GateTexts.confirmed(false, true))
    }

    @Test
    fun everyNewReasonHasItsOwnGateLine() {
        val reasons = listOf("reconfirm", "router-mismatch", "router-silent", "key-changed", "address-only", "not-confirmed", "store-unavailable", "no-wifi", "network-unknown")
        assertEquals(reasons.size, reasons.map(GateTexts::gateReason).toSet().size)
        assertNotEquals(GateTexts.gateReason("x"), GateTexts.gateReason("address-only"))
    }
}
