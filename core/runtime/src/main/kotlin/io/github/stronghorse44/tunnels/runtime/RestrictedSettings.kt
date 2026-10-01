package io.github.stronghorse44.tunnels.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import io.github.stronghorse44.tunnels.model.SpecialAccess

/**
 * Android's restricted settings (Android 13, extended as Enhanced Confirmation on 15): for an app installed
 * from a downloaded or local file, Settings refuses to switch on its notification access, usage access and a
 * few others ("App was denied access") until the user opens the app's App info, taps the ⋮ menu and chooses
 * Allow restricted settings. Android marks an app this way on its first install only; updates keep the mark
 * or the user's allowance.
 *
 * Whether the user already allowed it is a restricted-read app op that apps cannot see, so Tunnels cannot
 * tell; it can only tell whether it was installed from a file, and explains the step accordingly.
 */
object RestrictedSettings {
    /** Whether this app's last install came from a downloaded or local file: Android restricts the settings then. */
    fun installedFromFile(context: Context): Boolean = runCatching {
        val source = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
        source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
    }.getOrDefault(false)

    /** This app's App info screen, where the ⋮ menu holds Allow restricted settings. */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** The screen for this app alone that grants [access] (e.g. Tunnels' own Usage access switch), when it names one. */
    fun directIntent(context: Context, access: SpecialAccess): Intent? = access.direct?.let { d ->
        Intent(d.action).apply {
            if (d.packageUri) data = Uri.parse("package:${context.packageName}")
            d.extras.forEach { (k, v) -> putExtra(k, v) }
        }
    }

    /**
     * The general screen for [access]. In-app flows (e.g. the Shizuku connect screen) are not exported, and since
     * Android 12 an implicit intent only reaches them when the package is set, so an action this app answers itself
     * gets the package; this app is always visible to itself, so that lookup needs no package-visibility grant.
     */
    fun generalIntent(context: Context, access: SpecialAccess): Intent {
        val intent = Intent(access.settingsAction).apply {
            if (access.settingsAction.contains("APPLICATION_DETAILS") || access.settingsAction.contains("UNKNOWN_APP")) {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        val inApp = Intent(intent).setPackage(context.packageName)
        return if (context.packageManager.resolveActivity(inApp, 0) != null) inApp else intent
    }

    /**
     * Opens the screen that grants [access]: this app's own switch when the phone has that screen, else the general
     * one. Tried by starting it rather than by asking PackageManager first, which package visibility can blind to
     * Settings. False when neither opened.
     */
    fun open(context: Context, access: SpecialAccess): Boolean {
        directIntent(context, access)?.let { direct ->
            try {
                context.startActivity(direct)
                return true
            } catch (_: ActivityNotFoundException) {
                // An older or trimmed Settings: fall through to the general list.
            } catch (_: SecurityException) {
            }
        }
        return runCatching { context.startActivity(generalIntent(context, access)) }.isSuccess
    }
}
