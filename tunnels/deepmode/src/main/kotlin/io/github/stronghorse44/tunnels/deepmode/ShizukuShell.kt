package io.github.stronghorse44.tunnels.deepmode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger

/** Where the Shizuku app stands right now: installed, binder alive, permission granted. */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val granted: Boolean,
    /** Server version when running (13 for current builds). */
    val version: Int?,
) {
    val reason: String
        get() = when {
            !installed -> DeepKeys.REASON_NOT_INSTALLED
            !running -> DeepKeys.REASON_NOT_RUNNING
            else -> DeepKeys.REASON_NOT_GRANTED
        }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"

        /** How long a [cached] status is reused; findings are mapped in batches on the main thread. */
        const val CACHE_MILLIS = 2_000L

        @Volatile
        private var cache: Pair<Long, ShizukuStatus>? = null

        /**
         * [read] at most once per [CACHE_MILLIS]: the ping and permission check are binder calls to the
         * Shizuku server, and the findings flow asks for actions of every finding on each emission.
         */
        fun cached(context: Context): ShizukuStatus {
            val now = SystemClock.elapsedRealtime()
            cache?.let { (at, status) -> if (now - at < CACHE_MILLIS) return status }
            return read(context).also { cache = now to it }
        }

        /** Forgets the cached status, e.g. once a permission result or binder event arrives. */
        fun invalidate() {
            cache = null
        }

        /** A package lookup plus a binder ping and a permission check. Safe without Shizuku present. */
        fun read(context: Context): ShizukuStatus {
            val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val installed = running || runCatching {
                context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
                true
            }.getOrDefault(false)
            val granted = running && runCatching {
                !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            val version = if (running) runCatching { Shizuku.getVersion() }.getOrNull() else null
            return ShizukuStatus(installed, running, granted, version)
        }

        /** Intent that opens the Shizuku app, or null when it is not installed. */
        fun launchIntent(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Binds the [DeepShellService] through Shizuku for the duration of one [withShell] block and runs
 * commands in it. Every block binds and unbinds, so no shell process lingers between scans.
 */
class ShizukuShell(private val context: Context) {
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, DeepShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("deep")
        .debuggable(false)
        .version(SERVICE_VERSION)
        .tag("deep")

    private val attempts = AtomicInteger()

    /** How many times a UserService binding was attempted this process; tests assert zero without Shizuku. */
    val bindAttempts: Int get() = attempts.get()

    /** Binds, runs [block] with a live shell, unbinds. Throws [IllegalStateException] when the shell cannot start. */
    suspend fun <T> withShell(block: suspend (Session) -> T): T {
        val (shell, connection) = connect()
        return try {
            block(Session(shell))
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    /** One command in a fresh shell; for actions. */
    suspend fun run(command: String): String = withShell { it.run(command) }

    /** A bound shell. Commands are blocking binder calls, so they run on [Dispatchers.IO]. */
    class Session internal constructor(private val shell: IDeepShell) {
        suspend fun run(command: String): String = withContext(Dispatchers.IO) { shell.runCommand(command) ?: "" }
    }

    private suspend fun connect(): Pair<IDeepShell, ServiceConnection> {
        val binder = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) binder.complete(service)
                else binder.completeExceptionally(IllegalStateException("${DeepKeys.REASON_SHELL_FAILED}: dead binder"))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder.completeExceptionally(IllegalStateException("${DeepKeys.REASON_SHELL_FAILED}: disconnected"))
            }
        }
        attempts.incrementAndGet()
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            throw IllegalStateException("${DeepKeys.REASON_SHELL_FAILED}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val service = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { binder.await() } ?: run {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            throw IllegalStateException("${DeepKeys.REASON_SHELL_FAILED} within ${BIND_TIMEOUT_MILLIS / 1000} s")
        }
        return IDeepShell.Stub.asInterface(service) to connection
    }

    companion object {
        /** Bump when the service's AIDL or behaviour changes so Shizuku restarts a stale process. */
        const val SERVICE_VERSION = 1
        const val BIND_TIMEOUT_MILLIS = 15_000L
    }
}
