package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.FindingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionConfirmationTest {
    @Test
    fun destructivePerformAsksFirst() {
        val revoke = FindingAction.Perform("Revoke network", destructive = true) { "done" }
        val prompt = ActionConfirmation.prompt(revoke)
        assertEquals("Revoke network? This changes settings on your phone.", prompt?.question)
        assertEquals("Revoke network", prompt?.confirm)
        assertEquals("Cancel", prompt?.cancel)
    }

    @Test
    fun otherActionsRunAtOnce() {
        assertNull(ActionConfirmation.prompt(FindingAction.Perform("Close it") { "asks for a second tap itself" }))
        assertNull(ActionConfirmation.prompt(FindingAction.OpenAppDetails("a.b")))
        assertNull("Android's own dialog confirms an uninstall", ActionConfirmation.prompt(FindingAction.RequestUninstall("a.b")))
        assertNull(ActionConfirmation.prompt(FindingAction.OpenSettings("android.settings.SETTINGS", "Settings")))
    }

    @Test
    fun reversibleTogglesRunOnOneTap() {
        // Emissions' Silence and Turn off, Surroundings' mutes: destructive (the screen rescans) but reversible, no dialog.
        assertNull(ActionConfirmation.prompt(FindingAction.Perform("Silence", destructive = true, reversible = true) { "" }))
        assertNull(ActionConfirmation.prompt(FindingAction.Perform("Mute all 3", destructive = true, reversible = true) { "" }))
    }
}
