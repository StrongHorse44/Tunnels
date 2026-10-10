package io.github.stronghorse44.tunnels.emissions

import android.content.Context
import io.github.stronghorse44.tunnels.deepmode.ShizukuShell
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.delay

/**
 * Silence and Restore through the Shizuku shell, shared by the tunnel screen and the Quick Settings tile. One shell
 * session covers the read before, the commands and the reads after; every change is checked by reading the states
 * again, and the one-line result says what changed and what did not. The Restore memory is one row of the encrypted
 * settings table ([EmissionsKeys.RESTORE_SETTING]), not exported.
 */
class Silencer(private val context: Context, private val shell: ShizukuShell = ShizukuShell(context)) {
    private suspend fun store() = TunnelsRuntime.get(context).store

    suspend fun remembered(): Set<Emitter> = RestoreSet.decode(store().setting(EmissionsKeys.RESTORE_SETTING))

    /** Remembers what is on in [r] on top of what an earlier Silence left, for a Restore by hand or by the shell. */
    suspend fun remember(r: EmissionsReading) {
        val s = store()
        s.putSetting(EmissionsKeys.RESTORE_SETTING, RestoreSet.merge(s.setting(EmissionsKeys.RESTORE_SETTING), r.on))
    }

    suspend fun forget() = store().putSetting(EmissionsKeys.RESTORE_SETTING, null)

    /** Turns off everything that is on (or unknown), remembering what was on. */
    suspend fun silence(): String = inShell { read, exec ->
        val before = read()
        val steps = ShellPlan.silence(before)
        if (steps.isEmpty()) return@inShell "Nothing to silence: every transmitter Android reports is already off."
        remember(before)
        val errors = exec(steps)
        val after = settle(read) { r -> before.on.none { r.state(it) == EmitterState.ON } }
        SilenceOutcome.silence(before, after, errors).summary
    }

    /** Turns [emitters] off, for one finding or one row; nothing is remembered. */
    suspend fun turnOff(emitters: List<Emitter>): String = inShell { read, exec ->
        val before = read()
        val steps = emitters.mapNotNull { ShellPlan.silenceStep(it, before) }
        if (steps.isEmpty()) return@inShell "Nothing Tunnels can turn off here: use the Settings button."
        val errors = exec(steps)
        val after = settle(read) { r -> emitters.none { r.state(it) == EmitterState.ON } }
        SilenceOutcome(
            done = emitters.filter { before.state(it) != EmitterState.OFF && after.state(it) == EmitterState.OFF },
            missed = emitters.filter { after.state(it) == EmitterState.ON },
            missedLabel = "Still on",
            errors = errors,
        ).summary
    }

    /** Turns back on what the last Silence turned off; what does not come back stays remembered. */
    suspend fun restore(): String {
        val remembered = remembered()
        if (remembered.isEmpty()) return "Nothing to restore."
        return inShell { read, exec ->
            val errors = exec(ShellPlan.restore(remembered, read()))
            val after = settle(read) { r -> remembered.none { r.state(it) == EmitterState.OFF } }
            val outcome = SilenceOutcome.restore(remembered, after, errors)
            store().putSetting(EmissionsKeys.RESTORE_SETTING, RestoreSet.encode(outcome.missed))
            outcome.summary
        }
    }

    /**
     * Runs [block] in one shell session with a reader of the current states and a runner of steps; a one-line reason
     * instead when Shizuku is not ready or the shell does not start.
     */
    private suspend fun inShell(
        block: suspend (read: suspend () -> EmissionsReading, exec: suspend (List<ShellStep>) -> Map<Emitter, String>) -> String,
    ): String {
        val access = EmissionsProbe.shellAccess(context)
        if (access != ShellAccess.READY) return notReady(access)
        return try {
            shell.withShell { sh ->
                val command: suspend (String) -> String = { sh.run(it) }
                block({ EmissionsProbe.readIn(context, withApps = false, command).reading }, { steps -> runSteps(steps, command) })
            }
        } catch (e: IllegalStateException) {
            "The Shizuku shell did not start: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Runs each step's commands until one does not read as a failure; returns the last failure line per transmitter. */
    private suspend fun runSteps(steps: List<ShellStep>, command: suspend (String) -> String): Map<Emitter, String> {
        val errors = LinkedHashMap<Emitter, String>()
        for (step in steps) {
            var failure: String? = null
            for (c in step.commands) {
                failure = ShellOutput.firstFailure(command(c))
                if (failure == null) break
            }
            if (failure != null) errors[step.emitter] = failure
        }
        return errors
    }

    /** Radios take a moment to switch: reads again until [done] or about four seconds have passed. */
    private suspend fun settle(read: suspend () -> EmissionsReading, done: (EmissionsReading) -> Boolean): EmissionsReading {
        var r = read()
        repeat(SETTLE_TRIES) {
            if (done(r)) return r
            delay(SETTLE_STEP_MILLIS)
            r = read()
        }
        return r
    }

    companion object {
        private const val SETTLE_TRIES = 5
        private const val SETTLE_STEP_MILLIS = 800L

        fun notReady(access: ShellAccess): String = when (access) {
            ShellAccess.NOT_INSTALLED -> "Shizuku is not installed: turn each transmitter off from its Settings button."
            ShellAccess.NOT_RUNNING -> "Shizuku is not running: start it, or turn each transmitter off from its Settings button."
            ShellAccess.NOT_GRANTED -> "Shizuku has not granted Tunnels access: grant it, or use the Settings buttons."
            ShellAccess.READY -> "Ready."
        }
    }
}
