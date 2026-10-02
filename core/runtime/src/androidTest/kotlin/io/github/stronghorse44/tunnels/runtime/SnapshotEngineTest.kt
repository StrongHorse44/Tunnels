package io.github.stronghorse44.tunnels.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.DerivedTunnel
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the engine with a fake module against the real encrypted store on the device. */
@RunWith(AndroidJUnit4::class)
class SnapshotEngineTest {
    private class FakeModule(var granted: Boolean) : TunnelModule {
        override val id = TunnelCatalog.all.first { it.id == "permissions" }.id
        override val requiredPermissions = emptyList<PermissionSpec>()
        override suspend fun scan(progress: ScanProgress) = listOf(
            Observation(id, "com.example.app", "perm:CAMERA", if (granted) "granted" else "denied"),
        )
        override val rules: List<FindingRule> = listOf(
            Rules.perSubject("CAMERA_GRANTED", Severity.NOTICE) { _, o -> if (o.any { it.value == "granted" }) "camera granted" else null },
            Rules.onChange("PERMISSION_CHANGED", Severity.WARN) { "changed ${it.key.key}" },
        )
        override fun actionsFor(draft: FindingDraft) = listOf(FindingAction.OpenAppDetails(draft.subject))
    }

    @Test
    fun scanDiffsAndDerivesFindings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TunnelsStore.get(context)
        val module = FakeModule(granted = false)
        val engine = SnapshotEngine(TunnelRegistry(context, mapOf(module.id to module)), store)

        val first = engine.scan(listOf(module.id))
        assertEquals(1, first.observations)
        assertEquals(0, first.newFindings)           // no state finding (denied), no change finding on first scan

        module.granted = true
        val second = engine.scan(listOf(module.id))
        assertEquals(2, second.newFindings)          // CAMERA_GRANTED (state) + PERMISSION_CHANGED (sticky)

        module.granted = false
        val third = engine.scan(listOf(module.id))
        assertEquals(1, third.clearedFindings)       // state finding cleared; sticky one stays
        val remaining = store.dao.findingsFor(module.id)
        assertTrue(remaining.all { it.sticky })
        assertTrue(store.dao.snapshots().size >= 3)
    }

    /** A sensor tunnel with one adjustable reading. */
    private class Reading(override val id: String, var value: String) : TunnelModule {
        override val requiredPermissions = emptyList<PermissionSpec>()
        override suspend fun scan(progress: ScanProgress) = listOf(Observation(id, "device", "reading", value))
        override val rules: List<FindingRule> = emptyList()
        override fun actionsFor(draft: FindingDraft) = emptyList<FindingAction>()
    }

    /** Copies its source's reading and raises a finding when it is "high". */
    private class Echo(source: String) : DerivedTunnel {
        override val id = TunnelCatalog.CROSSROADS
        override val sources = setOf(source)
        override val requiredPermissions = emptyList<PermissionSpec>()
        override fun derive(input: DerivedInput) =
            input.sources.values.flatMap { it.observations }.map { Observation(id, it.subject, "echo", it.value) }
        override val rules: List<FindingRule> = listOf(Rules.perSubject("HIGH", Severity.WARN) { _, o -> if (o.any { it.value == "high" }) "high" else null })
        override fun actionsFor(draft: FindingDraft) = listOf(FindingAction.OpenAppDetails(draft.subject))
    }

    @Test
    fun derivedTunnelsJoinTheSameSnapshotAndUnchangedChecksStoreNothing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TunnelsStore.get(context)
        val sensor = Reading("silicon", "low")
        val echo = Echo(sensor.id)
        val engine = SnapshotEngine(TunnelRegistry(context, mapOf(sensor.id to sensor, echo.id to echo)), store)

        val first = engine.scan(listOf(sensor.id))
        assertTrue(first.stored)
        assertEquals(listOf("low"), store.dao.observations(first.snapshotId, echo.id).map { it.value })

        // Nothing changed: a background check stores no snapshot and raises nothing.
        val quiet = engine.scan(listOf(sensor.id), storeIfUnchanged = false)
        assertFalse(quiet.stored)
        assertTrue(quiet.added.isEmpty())

        sensor.value = "high"
        val changed = engine.scan(listOf(sensor.id), storeIfUnchanged = false)
        assertTrue(changed.stored)
        assertEquals(listOf("${echo.id}|device|HIGH"), changed.added.map { it.id })
        assertEquals(listOf("high"), store.dao.observations(changed.snapshotId, echo.id).map { it.value })

        // Scanning the derived tunnel by name re-joins the stored data of its source.
        val byName = engine.scan(listOf(echo.id))
        assertTrue(byName.stored)
        assertEquals(listOf("high"), store.dao.observations(byName.snapshotId, echo.id).map { it.value })
        assertTrue(store.dao.observations(byName.snapshotId, sensor.id).isEmpty())
    }
}
