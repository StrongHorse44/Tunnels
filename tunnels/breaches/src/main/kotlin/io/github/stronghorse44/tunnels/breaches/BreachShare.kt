package io.github.stronghorse44.tunnels.breaches

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** The hand-off to Linx: the address of the held list and the intent that carries it. */
object BreachShare {
    /** `<this app's id>.breaches`: io.github.stronghorse44.tunnels.breaches in the release build (the manifest's `${applicationId}.breaches`). */
    fun authority(context: Context): String = ShareUri.authorityFor(context.packageName)

    fun uri(context: Context, token: String): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath(ShareUri.SEGMENT).appendPath(token).build()

    /**
     * ACTION_SEND for the list at [uri], to Linx's package alone (never a chooser: no other app should get it), with a
     * one-time read grant on that address.
     */
    fun sendIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(Catalogue.MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setPackage(BreachMessages.LINX_PACKAGE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .also { it.clipData = ClipData.newRawUri(null, uri) }

    /** Whether Linx is installed in this profile (Tunnels can see packages; the home screen already relies on it). */
    fun linxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(BreachMessages.LINX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
