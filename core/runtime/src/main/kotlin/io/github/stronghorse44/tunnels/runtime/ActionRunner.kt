package io.github.stronghorse44.tunnels.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.stronghorse44.tunnels.model.FindingAction

/** Executes a [FindingAction]: Settings deep links and system confirmation flows, or the tunnel's own code. */
object ActionRunner {
    /** Returns a one-line result for the UI, or null when the action opened another screen. */
    suspend fun run(context: Context, action: FindingAction): String? = when (action) {
        is FindingAction.OpenAppDetails -> open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", action.packageName, null)))
        is FindingAction.RequestUninstall -> open(context, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", action.packageName, null)))
        is FindingAction.OpenSettings -> open(context, Intent(action.action))
        is FindingAction.Perform -> action.run()
    }

    private fun open(context: Context, intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "No screen on this phone handles that."
    }
}
