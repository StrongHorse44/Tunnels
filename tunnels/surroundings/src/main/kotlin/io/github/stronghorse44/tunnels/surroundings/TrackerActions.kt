package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.FindingAction

/**
 * The actions a tracker finding and the tracker detail offer. All of them are [FindingAction.Perform]
 * values so the finding card, the detail panel and the engine run them the same way; each returns the
 * one line the screen shows afterwards.
 */
object TrackerActions {
    private const val TAG = "Surroundings"

    /** Android 13+ Safety Center (Intent.ACTION_SAFETY_CENTER); on Pixels with Play services it hosts "Unknown tracker alerts". */
    const val ACTION_SAFETY_CENTER = Intent.ACTION_SAFETY_CENTER

    const val LABEL_FIND_IT = "Find it"
    const val LABEL_ALERTS = "Unknown tracker alerts"

    /** Opens find-it mode for a tracker family, locked to [key] when one is known. */
    fun findIt(context: Context, type: TrackerType, key: String? = null): FindingAction = FindingAction.Perform(LABEL_FIND_IT) {
        try {
            context.startActivity(FindItActivity.intent(context, type, key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Find-it mode follows the signal: it gets warmer as you close in."
        } catch (e: Exception) {
            "Could not open find-it mode: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Android's own Unknown tracker alerts live in Safety Center (Settings → Safety & emergency) and run
     * through Google Play services. Resolution order, each verified at run time: Safety Center when
     * something handles its intent; otherwise the Settings home with a one-line explanation (the Settings
     * search has no public deep link).
     */
    fun unknownTrackerAlerts(context: Context): FindingAction = FindingAction.Perform(LABEL_ALERTS) { openUnknownTrackerAlerts(context) }

    fun openUnknownTrackerAlerts(context: Context): String {
        // SafetyCenterManager.isSafetyCenterEnabled is a system API, so whether the screen exists is judged
        // by resolution alone: the Settings app only exports the action while Safety Center is enabled.
        val safetyCenter = Intent(ACTION_SAFETY_CENTER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolves(context, safetyCenter)) {
            return if (launch(context, safetyCenter)) "Opened Safety Center: Unknown tracker alerts is under \"Safety & emergency\". Turn it on and use its manual scan." else fallback(context)
        }
        return fallback(context)
    }

    private fun fallback(context: Context): String {
        launch(context, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Safety Center is not available on this phone (it needs Google Play services); Tunnels' background monitor is the substitute."
    }

    private fun resolves(context: Context, intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startActivity failed: ${e.javaClass.simpleName}")
        false
    }

}
