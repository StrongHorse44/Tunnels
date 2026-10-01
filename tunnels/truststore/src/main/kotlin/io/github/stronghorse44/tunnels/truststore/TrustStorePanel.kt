package io.github.stronghorse44.tunnels.truststore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stronghorse44.tunnels.certs.CaRow
import io.github.stronghorse44.tunnels.certs.TrustStoreOverview
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

private const val WHY_USER_CAS_MATTER =
    "A user-installed CA can vouch for any website, so apps that trust user CAs (browsers do; most other apps since Android 7 do not) can have their traffic read or altered."

/** Counts, the user-installed CAs up front, and why they matter. Shown above the generic findings and observations. */
@Composable
fun TrustStorePanel(state: TunnelScreenState, actions: TunnelScreenActions, trustedCredentials: FindingAction) {
    val overview = remember(state.observations) { TrustStoreOverview.from(state.observations) }
    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.lastScan == null && overview.isEmpty) {
                Text("Scan to list every certificate authority this phone trusts.", style = MaterialTheme.typography.bodyMedium)
                Text(WHY_USER_CAS_MATTER, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Counter("System CAs", overview.systemCount, line)
                Counter("User CAs", overview.userCount, if (overview.userCount > 0) StatusColors.warn else StatusColors.ok)
                if (overview.distrusted.isNotEmpty()) Counter("Distrusted", overview.distrusted.size, StatusColors.blocker)
            }
            Text(WHY_USER_CAS_MATTER, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)

            if (overview.userCas.isEmpty()) {
                Text("No user-installed certificate authorities.", color = StatusColors.ok, style = MaterialTheme.typography.bodyMedium)
            } else {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Text("User-installed", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = StatusColors.warn, fontSize = 12.sp)
                overview.userCas.forEach { CaLine(it, StatusColors.warn) }
                Button(onClick = { actions.perform(trustedCredentials) }, modifier = Modifier.padding(top = 2.dp)) { Text(trustedCredentials.label) }
            }
            overview.distrusted.filterNot { it.isUser }.takeIf { it.isNotEmpty() }?.let { rows ->
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Text("Distrusted system roots", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = StatusColors.blocker, fontSize = 12.sp)
                rows.forEach { CaLine(it, StatusColors.blocker) }
            }
            if (overview.unreadableCount > 0 || overview.skippedCount > 0) {
                Text(
                    buildString {
                        if (overview.unreadableCount > 0) append("${overview.unreadableCount} unreadable")
                        if (overview.skippedCount > 0) { if (isNotEmpty()) append(" · "); append("${overview.skippedCount} not listed (cap)") }
                    },
                    style = MaterialTheme.typography.labelSmall, color = GlassColors.dim,
                )
            }
        }
    }
}

@Composable
private fun Counter(label: String, value: Int, color: Color) {
    Column {
        Text("$value", fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
    }
}

@Composable
private fun CaLine(row: CaRow, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val details = listOfNotNull(
                row.org.takeIf { it.isNotBlank() && it != row.subject },
                row.shortFingerprint.takeIf { it.isNotBlank() },
                row.keyAlgo.takeIf { it.isNotBlank() },
                row.expires.takeIf { it.isNotBlank() }?.let { "expires $it" },
            ).joinToString(" · ")
            Text(details, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
            row.distrusted?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall, color = StatusColors.blocker) }
        }
    }
    Spacer(Modifier.height(2.dp))
}
