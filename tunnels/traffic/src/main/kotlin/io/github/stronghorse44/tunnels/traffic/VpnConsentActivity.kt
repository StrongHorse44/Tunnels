package io.github.stronghorse44.tunnels.traffic

import android.content.ActivityNotFoundException
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Shows the system's one-time VPN consent dialog and closes. The tunnel gate's "Open setting" button
 * launches this through [ACTION], since no Settings screen grants VPN consent; the panel's START
 * button runs the same `prepare()` flow inline.
 */
class VpnConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated mid-dialog: the result callback finishes us
        val intent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (intent == null) {
            finish()
            return
        }
        try {
            consent.launch(intent)
        } catch (_: ActivityNotFoundException) {
            finish()
        }
    }

    companion object {
        /** Package-qualified action the gate opens as the "settings screen" for VPN consent. */
        const val ACTION = "io.github.stronghorse44.tunnels.traffic.action.VPN_CONSENT"
    }
}
