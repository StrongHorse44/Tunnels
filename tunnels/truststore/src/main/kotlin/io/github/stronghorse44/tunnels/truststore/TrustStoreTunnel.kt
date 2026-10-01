package io.github.stronghorse44.tunnels.truststore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.certs.CertSummary
import io.github.stronghorse44.tunnels.certs.TrustStoreKeys
import io.github.stronghorse44.tunnels.certs.TrustStoreRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.coroutines.coroutineContext

/**
 * Trust store: every certificate authority this phone trusts, from the `AndroidCAStore` keystore.
 * System roots ship with the OS; user roots were installed by hand (or by an app that asked), and
 * apps that opt into trusting them can have their TLS traffic intercepted. Needs no permission.
 */
class TrustStoreTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = TrustStoreKeys.TUNNEL_ID
    override val requiredPermissions: List<PermissionSpec> = emptyList()
    override val rules: List<FindingRule> = TrustStoreRules.all

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null) }
        val aliases = store.aliases().toList().sorted()
        val out = ArrayList<Observation>(aliases.size * TrustStoreKeys.certificateKeys.size + TrustStoreKeys.summaryKeys.size)
        var system = 0
        var user = 0
        var unreadable = 0
        var skipped = 0
        aliases.forEachIndexed { index, alias ->
            coroutineContext.ensureActive()
            progress.report(index, aliases.size, alias.substringBefore(':'))
            val source = TrustStoreKeys.sourceOf(alias)
            if (source == null || (source == TrustStoreKeys.SOURCE_SYSTEM && system >= MAX_PER_SOURCE) || (source == TrustStoreKeys.SOURCE_USER && user >= MAX_PER_SOURCE)) {
                skipped++
                return@forEachIndexed
            }
            val summary = try {
                (store.getCertificate(alias) as? X509Certificate)?.let(CertSummary::of)
            } catch (e: Exception) {
                null
            }
            if (summary == null) {
                unreadable++
                return@forEachIndexed
            }
            out += TrustStoreKeys.observations(alias, summary)
            if (source == TrustStoreKeys.SOURCE_USER) user++ else system++
        }
        progress.report(aliases.size, aliases.size, "summary")
        out += TrustStoreKeys.summaryObservations(system, user, unreadable, skipped)
        return out
    }

    /** Every trust store finding leads to the same two places: the Trusted credentials screen and Security settings. */
    override fun actionsFor(draft: FindingDraft): List<FindingAction> = listOf(trustedCredentialsAction, securitySettingsAction)

    val trustedCredentialsAction: FindingAction = FindingAction.Perform("Trusted credentials") { openTrustedCredentials() }
    val securitySettingsAction: FindingAction = FindingAction.OpenSettings(Settings.ACTION_SECURITY_SETTINGS, "Security settings")

    /**
     * Opens AOSP Settings > Trusted credentials (user tab), where a user CA can be removed and a
     * system CA disabled. Falls back to Security settings when no such screen exists on this build.
     */
    private suspend fun openTrustedCredentials(): String = withContext(Dispatchers.Main.immediate) {
        for (action in TRUSTED_CREDENTIALS_ACTIONS) {
            if (start(Intent(action))) return@withContext "Opened Trusted credentials. Remove a user CA from the User tab; come back and the list rescans."
        }
        if (start(Intent(Settings.ACTION_SECURITY_SETTINGS))) {
            "No Trusted credentials screen on this phone; opened Security settings instead (look for Encryption & credentials)."
        } else {
            "No Settings screen on this phone handles that."
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    override val showObservations: Boolean get() = true

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        TrustStorePanel(state, actions, trustedCredentialsAction)
    }

    companion object {
        const val ANDROID_CA_STORE = "AndroidCAStore"

        /** A phone ships ~150 system roots; anything beyond this per source is counted, not listed. */
        const val MAX_PER_SOURCE = 600

        /** AOSP's TrustedCredentialsSettings answers the first; older builds only the second. */
        val TRUSTED_CREDENTIALS_ACTIONS = listOf(
            "com.android.settings.TRUSTED_CREDENTIALS_USER",
            "com.android.settings.TRUSTED_CREDENTIALS",
        )
    }
}
