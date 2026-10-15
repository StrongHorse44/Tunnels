package io.github.stronghorse44.tunnels.runtime

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.engine.ActionConfirmation
import io.github.stronghorse44.tunnels.model.FindingAction

/**
 * [run] behind the one generic confirmation (audit L7, review Q6): an irreversible destructive [FindingAction.Perform]
 * or an uninstall first asks "<label>? This changes settings on your phone." with Cancel and <label>
 * ([ActionConfirmation]); reversible toggles and every other action run at once. The dialog is emitted where this is called. "Close it" keeps its own two-tap flow (it is not marked destructive).
 */
@Composable
fun rememberConfirmedAction(run: (FindingAction) -> Unit): (FindingAction) -> Unit {
    var pending by remember { mutableStateOf<FindingAction?>(null) }
    val current by rememberUpdatedState(run)
    val action = pending
    val prompt = action?.let(ActionConfirmation::prompt)
    if (action != null && prompt != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text(prompt.question, color = GlassColors.text) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    current(action)
                }) { Text(prompt.confirm) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(prompt.cancel, color = GlassColors.dim) } },
        )
    }
    return remember {
        { a: FindingAction ->
            val ask = ActionConfirmation.prompt(a) != null
            if (ask) pending = a
            if (!ask) current(a)
        }
    }
}

/** [actions] for a tunnel's own panel, with [perform] behind [rememberConfirmedAction]. */
internal class ConfirmingActions(
    private val actions: TunnelScreenActions,
    private val confirmed: (FindingAction) -> Unit,
) : TunnelScreenActions by actions {
    override fun perform(action: FindingAction) = confirmed(action)
}
