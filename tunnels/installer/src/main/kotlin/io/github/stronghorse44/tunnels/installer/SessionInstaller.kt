package io.github.stronghorse44.tunnels.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** A status update for an install session, as delivered by the system. */
data class SessionUpdate(val sessionId: Int, val status: Int, val message: String?, val confirmIntent: Intent?)

/** Process-wide channel from [InstallResultReceiver] to whoever is watching a session. */
object InstallEvents {
    private val _updates = MutableSharedFlow<SessionUpdate>(replay = 8, extraBufferCapacity = 16)
    val updates: SharedFlow<SessionUpdate> = _updates
    fun emit(update: SessionUpdate) {
        _updates.tryEmit(update)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        InstallEvents.emit(
            SessionUpdate(
                sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                confirmIntent = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else null,
            ),
        )
    }
}

object SessionInstaller {
    /** Writes [apks] into a new session and commits it. Returns the session id; results arrive via [InstallEvents]. */
    suspend fun install(
        context: Context,
        packageName: String,
        apks: List<File>,
        onProgress: (Float) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val total = apks.sumOf { it.length() }.coerceAtLeast(1)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(total)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            // Lets Android skip the prompt for updates to apps Tunnels installed, where policy allows.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                var written = 0L
                apks.forEachIndexed { i, apk ->
                    session.openWrite("part_$i.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                written += r
                                onProgress(written.toFloat() / total)
                            }
                        }
                        session.fsync(out)
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
        sessionId
    }
}
