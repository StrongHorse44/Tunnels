package io.github.stronghorse44.tunnels.traffic

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Shows the system's one-time VPN consent dialog and closes. The tunnel gate's "Open setting" button
 * launches this through [ACTION], since no Settings screen grants VPN consent; the panel's START
 * button runs the same flow inline. Both go through [VpnStatus.consentIntent], which never asks the
 * system while another VPN is connected, because asking would disconnect that VPN.
 */
class VpnConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated mid-dialog: the result callback finishes us
        // Null when consent is already given, or when a VPN is up (then the panel explains what to do).
        val intent = VpnStatus.consentIntent(this)
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
