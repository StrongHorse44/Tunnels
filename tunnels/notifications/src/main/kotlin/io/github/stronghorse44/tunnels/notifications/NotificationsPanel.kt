package io.github.stronghorse44.tunnels.notifications

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.notifrules.NotifAggregator
import io.github.stronghorse44.tunnels.notifrules.NotifSummary
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

/** This week's total, the listener's state, the five noisiest apps, and what is (not) stored. */
@Composable
fun NotificationsPanel(state: TunnelScreenState) {
    val summary = remember(state.observations) { NotifSummary.from(state.observations) }
    val line = LineColors.of(MetroLine.ACTIVITY)
    GlassPanel(Modifier.fillMaxWidth(), tint = line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.lastScan == null) {
                Text("Scan to count what the listener has recorded so far.", style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${summary.total7}", fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = line)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "notifications from ${summary.appsActive7} app${if (summary.appsActive7 == 1) "" else "s"}" +
                            if (summary.appsNoisy > 0) " · ${summary.appsNoisy} noisy" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassColors.dim,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                ListenerLine(summary)
                if (!summary.hasData) {
                    Text(
                        "Nothing counted yet. From now on each notification adds one line of flags; come back after a day or two.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassColors.dim,
                    )
                } else if (summary.top.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text("Noisiest · per day", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
                    val max = summary.top.first().perDay7.coerceAtLeast(0.1)
                    summary.top.forEach { app ->
                        AppRow(app, fraction = (app.perDay7 / max).toFloat(), color = line)
                    }
                }
            }
            Text(
                "Only counts and flags are stored: which app, how often and at what hour, importance, lock-screen visibility, " +
                    "category, ongoing and silent. Notification text never is.",
                style = MaterialTheme.typography.labelSmall,
                color = GlassColors.dim,
            )
        }
    }
}

@Composable
private fun ListenerLine(summary: NotifSummary) {
    val (color, text) = when {
        !summary.accessGranted -> StatusColors.warn to "Notification access not granted"
        summary.listenerConnected && summary.dropped > 0 ->
            StatusColors.warn to "Listener connected, ${summary.dropped} post${if (summary.dropped == 1) "" else "s"} not recorded"
        summary.listenerConnected -> StatusColors.ok to "Listener connected and counting"
        else -> StatusColors.warn to "Listener not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun AppRow(app: NotifSummary.TopApp, fraction: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (app.night7 > 0) {
                Text("${app.night7} at night", style = MaterialTheme.typography.labelSmall, color = GlassColors.dim)
                Spacer(Modifier.width(8.dp))
            }
            Text("${NotifAggregator.formatRate(app.perDay7)}/day", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
            Spacer(Modifier.width(8.dp))
            Text("${app.count7}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = GlassColors.dim)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(GlassColors.text.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).background(color.copy(alpha = 0.85f)))
        }
    }
}
