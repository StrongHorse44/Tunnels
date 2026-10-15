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
        assertEquals("Revoke network? This changes the app's state.", prompt?.question)
        assertEquals("Revoke network", prompt?.confirm)
        assertEquals("Cancel", prompt?.cancel)
    }

    @Test
    fun otherActionsRunAtOnce() {
        assertNull(ActionConfirmation.prompt(FindingAction.Perform("Close it") { "asks for a second tap itself" }))
        assertNull(ActionConfirmation.prompt(FindingAction.OpenAppDetails("a.b")))
        assertNull(ActionConfirmation.prompt(FindingAction.RequestUninstall("a.b")))
        assertNull(ActionConfirmation.prompt(FindingAction.OpenSettings("android.settings.SETTINGS", "Settings")))
    }
}
