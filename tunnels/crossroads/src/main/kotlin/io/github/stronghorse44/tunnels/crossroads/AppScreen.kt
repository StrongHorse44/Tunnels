package io.github.stronghorse44.tunnels.crossroads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.crossrules.AppDossier
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.FindingCard
import io.github.stronghorse44.tunnels.runtime.ToolScaffold

@Composable
fun AppScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tint = LineColors.of(MetroLine.INSPECT)
    val dossier = state.dossier
    ToolScaffold(title = dossier?.label ?: state.packageName, subtitle = state.packageName, tint = tint, onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.installed) {
                Text("Not installed any more: what follows is from the last scans.", style = MaterialTheme.typography.bodySmall, color = StatusColors.warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.installed) {
                    OutlinedButton(onClick = { vm.run(FindingAction.OpenAppDetails(state.packageName)) }) { Text("App info") }
                    if (!state.system) OutlinedButton(onClick = { vm.run(FindingAction.RequestUninstall(state.packageName)) }) { Text("Uninstall", color = StatusColors.warn) }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warn) }

            if (state.findings.isNotEmpty()) {
                Text("Open findings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.findings.forEach { f -> FindingCard(f, onAction = vm::run, onDismiss = { vm.dismiss(f) }) }
            } else if (state.loaded) {
                Text("No open findings for this app.", style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim)
            }

            dossier?.sections?.forEach { section -> SectionPanel(section) }
            if (dossier != null && dossier.missing.isNotEmpty()) {
                Text(
                    "Nothing yet from ${dossier.missing.joinToString(", ")}: scan ${if (dossier.missing.size == 1) "it" else "them"} to fill this in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
            }
            if (state.loaded && dossier != null && dossier.sections.isEmpty()) {
                Text("No tunnel has scanned this app yet.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionPanel(section: AppDossier.Section) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            section.rows.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    Text(row.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim, modifier = Modifier.weight(0.38f))
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End,
                        color = when (row.tone) {
                            AppDossier.Tone.WARN -> StatusColors.warn
                            AppDossier.Tone.GOOD -> StatusColors.ok
                            AppDossier.Tone.PLAIN -> GlassColors.text
                        },
                        modifier = Modifier.weight(0.62f),
                    )
                }
            }
        }
    }
}
