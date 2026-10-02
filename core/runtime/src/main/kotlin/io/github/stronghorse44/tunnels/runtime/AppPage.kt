package io.github.stronghorse44.tunnels.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The per-app page (Crossroads' AppActivity) by its same-package action, so any screen that shows an app can link to
 * it without depending on the module that draws it.
 */
object AppPage {
    const val ACTION = "io.github.stronghorse44.tunnels.action.APP"
    const val EXTRA_PACKAGE = "io.github.stronghorse44.tunnels.extra.PACKAGE"

    /** Package names look like `a.b`; subjects such as `device`, `summary` or `uid:1000` are not apps. */
    fun isApp(subject: String): Boolean =
        subject.contains('.') && !subject.contains(':') && subject.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    fun intent(context: Context, packageName: String): Intent =
        Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_PACKAGE, packageName)

    /** Whether this build has the page. */
    fun available(context: Context): Boolean =
        context.packageManager.resolveActivity(Intent(ACTION).setPackage(context.packageName), PackageManager.ResolveInfoFlags.of(0)) != null

    fun open(context: Context, packageName: String) {
        runCatching { context.startActivity(intent(context, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
