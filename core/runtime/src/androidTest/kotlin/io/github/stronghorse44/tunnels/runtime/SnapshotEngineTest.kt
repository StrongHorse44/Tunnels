package io.github.stronghorse44.tunnels.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.engine.Rules
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
}
