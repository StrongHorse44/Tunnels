package io.github.stronghorse44.tunnels.deepmode

import android.content.Context

/**
 * The Shizuku user service. Shizuku starts this class in its own process as the shell user (uid 2000),
 * `daemon = false` so it dies with the app. It only runs what the app sends and returns the text; all
 * parsing stays in the app process. Both constructors are required by Shizuku (minify is off, so
 * nothing strips them).
 */
class DeepShellService : IDeepShell.Stub {
    private val runner = ShellRunner()

    constructor() : super()

    @Suppress("unused")
    constructor(context: Context) : super()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() = destroy()

    override fun runCommand(cmd: String?): String =
        if (cmd.isNullOrBlank()) "" else runCatching { runner.run(cmd) }.getOrElse { "[error] ${it.javaClass.simpleName}: ${it.message}" }
}
