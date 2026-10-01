package io.github.stronghorse44.tunnels.silicon

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
import io.github.stronghorse44.tunnels.attestation.KnownBootKeys
import io.github.stronghorse44.tunnels.attestation.PatchLevel
import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.attestation.SiliconRules
import io.github.stronghorse44.tunnels.attestation.VerifiedBootState
import io.github.stronghorse44.tunnels.common.GlassColors
import io.github.stronghorse44.tunnels.common.GlassPanel
import io.github.stronghorse44.tunnels.common.LineColors
import io.github.stronghorse44.tunnels.common.StatusColors
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState

/** What the last scan knows about verified boot, flattened for the card. */
internal class BootSummary(obs: List<Observation>) {
    private val device = obs.filter { it.subject == SiliconKeys.SUBJECT }
    private fun v(key: String) = SiliconKeys.value(device, key)

    val present = device.isNotEmpty()
    val error = v(SiliconKeys.ATTESTATION_ERROR)
    val state = v(SiliconKeys.BOOT_STATE)
    val locked = v(SiliconKeys.BOOT_LOCKED)
    val keyName = v(SiliconKeys.BOOT_KEY_NAME)
    val keyHash = v(SiliconKeys.BOOT_KEY_HASH)
    val bootHash = v(SiliconKeys.BOOT_HASH)
    val osPatch = v(SiliconKeys.OS_PATCH)?.toIntOrNull()
    val osPatchAge = v(SiliconKeys.OS_PATCH_AGE_DAYS)?.toLongOrNull()
    val vendorPatch = v(SiliconKeys.VENDOR_PATCH)?.toIntOrNull()
    val bootPatch = v(SiliconKeys.BOOT_PATCH)?.toIntOrNull()
    val osVersion = v(SiliconKeys.OS_VERSION)
    val level = v(SiliconKeys.ATTESTATION_LEVEL)
    val strongBox = v(SiliconKeys.STRONGBOX) == "true"
    val chainVerified = v(SiliconKeys.CHAIN_VERIFIED)
    val chainRoot = v(SiliconKeys.CHAIN_ROOT)
    val chainLength = v(SiliconKeys.CHAIN_LENGTH)
    val model = v(SiliconKeys.DEVICE_MODEL)
    val release = v(SiliconKeys.OS_RELEASE)
    val securityPatch = v(SiliconKeys.DEVICE_SECURITY_PATCH)
    val challenge = v(SiliconKeys.ATTESTATION_CHALLENGE)

    val unlocked: Boolean get() = locked == "false" || state == VerifiedBootState.UNVERIFIED.label || state == VerifiedBootState.FAILED.label
    val keyUnknown: Boolean get() = keyName == KnownBootKeys.UNKNOWN
    val patchOld: Boolean get() = (osPatchAge ?: 0) > SiliconRules.MAX_PATCH_AGE_DAYS

    /** One line for the header: the strongest statement the hardware supports. */
    val headline: String
        get() = when {
            error != null -> "Attestation unavailable"
            unlocked -> "Bootloader unlocked"
            keyUnknown -> "Signed with an unknown key"
            state == VerifiedBootState.SELF_SIGNED.label -> "Locked, running ${keyName?.substringBefore(" on ") ?: "a custom OS"}"
            state == VerifiedBootState.VERIFIED.label -> "Locked, running the stock OS"
            state != null -> "Verified boot: $state"
            else -> "No root of trust reported"
        }

    val color: Color
        get() = when {
            error != null -> GlassColors.dim
            unlocked -> StatusColors.blocker
            keyUnknown || patchOld -> StatusColors.warn
            else -> StatusColors.ok
        }
}

/** The verified-boot card shown above the generic findings and observations. */
@Composable
fun VerifiedBootCard(state: TunnelScreenState) {
    val summary = remember(state.observations) { BootSummary(state.observations) }
    val line = LineColors.of(MetroLine.SYSTEM)
    GlassPanel(Modifier.fillMaxWidth(), tint = if (summary.present) summary.color else line) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (summary.present) summary.color else GlassColors.dim))
                Spacer(Modifier.width(8.dp))
                Text("Verified boot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (!summary.present) {
                Text(
                    "Scan to ask the security hardware what it booted: the lock state, the key that signed the OS and the patch level it enforces. A throwaway key is attested and deleted; nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassColors.dim,
                )
                return@Column
            }
            Text(summary.headline, style = MaterialTheme.typography.bodyLarge, color = summary.color)

            summary.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = GlassColors.dim)
                Spacer(Modifier.height(2.dp))
                DeviceRows(summary)
                return@Column
            }

            Label("Boot")
            summary.state?.let { ValueRow("State", it) }
            summary.locked?.let { ValueRow("Bootloader", if (it == "true") "locked" else "unlocked") }
            summary.keyName?.let { ValueRow("Signed by", it) }
            summary.keyHash?.takeIf { it.isNotEmpty() }?.let { hash ->
                Text(SiliconKeys.groupHex(hash), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GlassColors.dim, lineHeight = 14.sp)
            }
            summary.bootHash?.takeIf { it != "none" }?.let { ValueRow("Boot image", "$it…") }

            Spacer(Modifier.height(2.dp))
            Label("Patch levels the hardware enforces")
            summary.osPatch?.let { level ->
                val age = summary.osPatchAge?.let { " · " + PatchLevel.describeAge(it) }.orEmpty()
                ValueRow("OS", PatchLevel.format(level) + age, if (summary.patchOld) StatusColors.warn else null)
            }
            summary.vendorPatch?.let { ValueRow("Vendor", PatchLevel.format(it)) }
            summary.bootPatch?.let { ValueRow("Boot", PatchLevel.format(it)) }
            summary.osVersion?.let { ValueRow("OS version", formatOsVersion(it)) }

            Spacer(Modifier.height(2.dp))
            Label("Attestation")
            summary.level?.let { ValueRow("Security level", it + if (summary.strongBox) " (StrongBox key)" else "") }
            summary.chainVerified?.let { verified ->
                val text = when {
                    verified == "true" -> "verified · " + (summary.chainRoot ?: "Google root")
                    verified == "false" -> "not signed by a Google root"
                    else -> verified
                }
                ValueRow("Chain of ${summary.chainLength ?: "?"}", text, if (verified == "true") null else GlassColors.dim)
            }
            summary.challenge?.takeIf { it != "matched" }?.let { ValueRow("Challenge", it, StatusColors.warn) }

            Spacer(Modifier.height(2.dp))
            DeviceRows(summary)
        }
    }
}

@Composable
private fun DeviceRows(summary: BootSummary) {
    Label("As the OS reports it")
    summary.model?.let { ValueRow("Model", it) }
    summary.release?.let { ValueRow("Android", it) }
    summary.securityPatch?.let { ValueRow("Security patch", it) }
}

@Composable
private fun Label(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = GlassColors.dim)
}

@Composable
private fun ValueRow(label: String, value: String, valueColor: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = GlassColors.dim, modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor ?: GlassColors.text, modifier = Modifier.weight(0.62f))
    }
}

/** 160000 -> "16.0.0"; anything unexpected is shown as is. */
internal fun formatOsVersion(raw: String): String {
    val n = raw.toIntOrNull() ?: return raw
    if (n < 10000) return raw
    return "${n / 10000}.${(n / 100) % 100}.${n % 100}"
}
