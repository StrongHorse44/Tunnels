package io.github.stronghorse44.tunnels.surroundings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.safetycenter.SafetyCenterManager
import android.util.Log
import io.github.stronghorse44.tunnels.ble.TrackerGuides
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.model.FindingAction

/**
 * The actions a tracker finding and the tracker detail offer. All of them are [FindingAction.Perform]
 * values so the finding card, the detail panel and the engine run them the same way; each returns the
 * one line the screen shows afterwards.
 */
object TrackerActions {
    private const val TAG = "Surroundings"

    /** Android 13+ Safety Center; on Pixels with Play services it hosts "Unknown tracker alerts". */
    const val ACTION_SAFETY_CENTER = "android.settings.SAFETY_CENTER"

    const val LABEL_FIND_IT = "Find it"
    const val LABEL_ALERTS = "Unknown tracker alerts"
    const val LABEL_IDENTIFY = "How to identify it"
    const val LABEL_DISABLE = "How to disable it"
    const val LABEL_REPORT = "Report it"

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
     * through Google Play services. Resolution order, each verified at run time: Safety Center when the
     * system says it is enabled and something handles the intent; otherwise the Settings home with an
     * explanation, since the Settings search has no public deep link.
     */
    fun unknownTrackerAlerts(context: Context): FindingAction = FindingAction.Perform(LABEL_ALERTS) { openUnknownTrackerAlerts(context) }

    fun openUnknownTrackerAlerts(context: Context): String {
        val safetyCenter = Intent(ACTION_SAFETY_CENTER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val enabled = runCatching { context.getSystemService(SafetyCenterManager::class.java)?.isSafetyCenterEnabled == true }.getOrDefault(false)
        if (enabled && resolves(context, safetyCenter)) {
            return if (launch(context, safetyCenter)) "Opened Safety Center: Unknown tracker alerts is under \"Safety & emergency\". Turn it on and use its manual scan." else fallback(context)
        }
        return fallback(context)
    }

    private fun fallback(context: Context): String {
        launch(context, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "This phone has no Unknown tracker alerts screen: it ships with Google Play services, which GrapheneOS does not run by default. " +
            "Opened Settings so you can search for it; Tunnels' background monitor is the substitute."
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

    /** The brand guide as finding actions: the card shows the text in its message line, the detail opens a sheet instead. */
    fun identify(type: TrackerType): FindingAction = FindingAction.Perform(LABEL_IDENTIFY) { TrackerGuides.of(type).identify }

    fun disable(type: TrackerType): FindingAction = FindingAction.Perform(LABEL_DISABLE) { TrackerGuides.of(type).disable }

    fun report(type: TrackerType): FindingAction = FindingAction.Perform(LABEL_REPORT) {
        (TrackerGuides.report + TrackerGuides.of(type).reportNote).joinToString(" ")
    }
}
