package io.github.stronghorse44.tunnels.permissions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.permrules.PermissionKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the emulator's real package list and checks the observation schema and the rules' output. */
@RunWith(AndroidJUnit4::class)
class PermissionsTunnelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val module = PermissionsTunnels().create(context).single()

    @Test
    fun scanProducesSchemaConformingObservations() = runBlocking {
        assertEquals("permissions", module.id)
        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue(reports > 0)
        assertTrue(obs.all { it.tunnelId == module.id })
        assertFalse("own package must be excluded", obs.any { it.subject == context.packageName })

        val bySubject = obs.groupBy { it.subject }
        assertTrue("the framework package is always installed", "android" in bySubject)
        assertEquals("true", bySubject["android"]!!.first { it.key == PermissionKeys.APP_SYSTEM }.value)

        for ((subject, list) in bySubject) {
            val keys = list.map { it.key }
            assertEquals("duplicate keys for $subject", keys.size, keys.toSet().size)
            assertTrue(subject, PermissionKeys.APP_LABEL in keys)
            assertTrue(subject, list.first { it.key == PermissionKeys.APP_SYSTEM }.value in setOf("true", "false"))
            assertTrue(subject, list.first { it.key == PermissionKeys.APP_TARGET_SDK }.value.toInt() > 0)
            assertTrue(subject, list.first { it.key == PermissionKeys.TOGGLE_NETWORK }.value in setOf(PermissionKeys.ON, PermissionKeys.OFF, PermissionKeys.NA))
            assertTrue(subject, list.first { it.key == PermissionKeys.TOGGLE_SENSORS }.value in setOf(PermissionKeys.ON, PermissionKeys.OFF, PermissionKeys.UNKNOWN))
            assertTrue(subject, list.count { PermissionKeys.isPermKey(it.key) } <= PermissionsTunnel.MAX_PERMISSIONS)
            for (o in list) {
                when {
                    PermissionKeys.isPermKey(o.key) -> assertTrue("${o.key}=${o.value}", o.value == PermissionKeys.GRANTED || o.value == PermissionKeys.DENIED)
                    o.key == PermissionKeys.APP_PERMS_OMITTED -> assertTrue(o.value.toInt() > 0)
                    o.key == PermissionKeys.ACCESS_ACCESSIBILITY -> assertTrue(o.value == PermissionKeys.ENABLED || o.value == PermissionKeys.INSTALLED)
                }
            }
        }
        // Stock Android has no OTHER_SENSORS; a Pixel on GrapheneOS reports on/off instead.
        assertTrue(obs.any { it.key == PermissionKeys.TOGGLE_NETWORK && it.value == PermissionKeys.ON })

        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), isFirstScan = true)) }
        assertTrue(drafts.all { it.evidence.isNotBlank() })
        assertTrue(drafts.none { it.sticky })
        for (d in drafts) {
            val actions = module.actionsFor(d)
            assertTrue(actions.first() is FindingAction.OpenAppDetails)
            val system = bySubject[d.subject]!!.first { it.key == PermissionKeys.APP_SYSTEM }.value == "true"
            assertEquals(!system, actions.any { it is FindingAction.RequestUninstall })
        }
    }
}
