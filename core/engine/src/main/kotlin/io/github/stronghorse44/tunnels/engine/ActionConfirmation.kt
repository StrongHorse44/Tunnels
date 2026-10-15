package io.github.stronghorse44.tunnels.engine

import io.github.stronghorse44.tunnels.model.FindingAction

/**
 * The one confirmation every destructive action gets before it runs (audit L7): a `Perform(destructive = true)` changes
 * state (Deep mode revoking a permission, Emissions turning a radio off), so one tap only asks. The copy is generic, never
 * per action. An uninstall needs none here: Android's own dialog confirms it.
 */
object ActionConfirmation {
    data class Prompt(val question: String, val confirm: String, val cancel: String = "Cancel")

    /** The question to ask before [action] runs, or null when it runs at once. */
    fun prompt(action: FindingAction): Prompt? =
        if (action is FindingAction.Perform && action.destructive) Prompt("${action.label}? This changes the app's state.", action.label) else null
}
