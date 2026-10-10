package io.github.stronghorse44.tunnels.emissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.deepmode.ShizukuShell
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.MetroLine
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.Stratum
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emissions on the emulator: no Shizuku, so the scan reads Android's app APIs only and never binds a shell. */
@RunWith(AndroidJUnit4::class)
class EmissionsSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerRegistersEmissionsWithNoPermissions() {
        val modules = EmissionsTunnels().create(context)
        assertEquals(listOf(EmissionsKeys.TUNNEL_ID), modules.map { it.id })
        val m = modules.single()
        assertTrue(m.requiredPermissions.isEmpty())
        assertTrue(m.specialAccess.isEmpty())
        assertEquals(MetroLine.NETWORK, m.info.line)
        assertEquals(Stratum.SURFACE, m.info.stratum)
        assertTrue(m is TunnelUi)
        assertEquals(EmissionsRules.all.size, m.rules.size)
    }

    @Test
    fun scanReportsEveryTransmitterAsSummariesOnly() = runBlocking {
        val tunnel = EmissionsTunnel(context)
        val obs = tunnel.scan(ScanProgress.NONE)
        val subjects = obs.map { it.subject }.toSet()
        Emitter.entries.forEach { e -> assertTrue(e.id, obs.any { it.subject == e.id && it.key == EmissionsKeys.STATE }) }
        // Rule 3: transmitters, location, the summary and counts; never an app, address or time.
        assertEquals(Emitter.entries.map { it.id }.toSet() + EmissionsKeys.LOCATION + EmissionsKeys.SUMMARY + EmissionsKeys.APPS, subjects)
        val reading = EmissionsObservations.from(obs)
        assertNotEquals(ShellAccess.READY, reading.shell)
        // Without the shell the hotspot cannot be read, so the phone is never reported as fully silent.
        assertEquals(EmitterState.UNKNOWN, reading.state(Emitter.HOTSPOT))
        assertNotEquals(QuietLevel.SILENT, reading.level)
        val ctx = RuleContext(EmissionsKeys.TUNNEL_ID, obs, emptyList(), isFirstScan = true)
        tunnel.rules.flatMap { it.evaluate(ctx) }.forEach { draft -> assertTrue(draft.kind, tunnel.actionsFor(draft).isNotEmpty()) }
    }

    @Test
    fun silenceWithoutShizukuExplainsAndNeverBinds() = runBlocking {
        val shell = ShizukuShell(context)
        val result = Silencer(context, shell).silence()
        assertTrue(result, result.startsWith("Shizuku"))
        assertEquals(0, shell.bindAttempts)
    }

    @Test
    fun everyFindingKindHasAnAction() {
        val tunnel = EmissionsTunnel(context)
        EmissionsRules.kinds.forEach { kind ->
            val draft = FindingDraft(EmissionsKeys.TUNNEL_ID, Emitter.NFC.id, kind, Severity.INFO, "")
            assertTrue(kind, tunnel.actionsFor(draft).isNotEmpty())
        }
    }
}
