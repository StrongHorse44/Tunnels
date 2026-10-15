package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.FindingAction

/**
 * The one confirmation an irreversible change, or a change to another app, gets before it runs (audit L7; review Q6):
 * a `Perform(destructive = true)` (Deep mode revoking a permission or the network, disabling an app, blocking clipboard
 * reads, turning scanning off, hiding private content). An uninstall gets none here: Android's own dialog confirms it,
 * and a second one in front of it adds nothing. Reversible toggles (`reversible = true`) run on
 * one tap: Emissions' Silence, Restore and Turn off, Surroundings' Mute, Mute all and Unmute. The copy is generic.
 */
object ActionConfirmation {
    data class Prompt(val question: String, val confirm: String, val cancel: String = "Cancel")

    /** The question to ask before [action] runs, or null when it runs at once. */
    fun prompt(action: FindingAction): Prompt? {
        val asks = (action is FindingAction.Perform && action.destructive && !action.reversible)
        return if (asks) Prompt("${action.label}? This changes settings on your phone.", action.label) else null
    }
}
