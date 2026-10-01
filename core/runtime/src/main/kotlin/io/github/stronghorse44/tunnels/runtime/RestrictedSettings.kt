package io.github.stronghorse44.tunnels.runtime

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

    /**
     * The screen that grants [access]: its screen for this app alone when the phone has one, else the general
     * one. In-app flows (e.g. the Shizuku connect screen) are not exported, and since Android 12 an implicit
     * intent only reaches them when the package is set, so a target this app answers itself gets the package.
     */
    fun settingsIntent(context: Context, access: SpecialAccess): Intent {
        val pm = context.packageManager
        val pkgUri = Uri.parse("package:${context.packageName}")
        val direct = access.direct?.let { d ->
            Intent(d.action).apply {
                if (d.packageUri) data = pkgUri
                d.extras.forEach { (k, v) -> putExtra(k, v) }
            }
        }?.takeIf { pm.resolveActivity(it, 0) != null }
        val intent = direct ?: Intent(access.settingsAction).apply {
            if (access.settingsAction.contains("APPLICATION_DETAILS") || access.settingsAction.contains("UNKNOWN_APP")) data = pkgUri
        }
        val inApp = Intent(intent).setPackage(context.packageName)
        return if (pm.resolveActivity(inApp, 0) != null) inApp else intent
    }
}
