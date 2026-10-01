package io.github.stronghorse44.tunnels.doors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the emulator's real package list and checks the observation schema and the rules' output. */
@RunWith(AndroidJUnit4::class)
class DoorsTunnelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val module = DoorsTunnels().create(context).single()

    private val countKeys = setOf(
        DoorsKeys.EXPORTED_ACTIVITIES, DoorsKeys.EXPORTED_SERVICES, DoorsKeys.EXPORTED_RECEIVERS, DoorsKeys.EXPORTED_PROVIDERS,
        DoorsKeys.UNPROTECTED_ACTIVITIES, DoorsKeys.UNPROTECTED_SERVICES, DoorsKeys.UNPROTECTED_RECEIVERS, DoorsKeys.UNPROTECTED_PROVIDERS,
        DoorsKeys.EXPORTED_UNPROTECTED, DoorsKeys.PROVIDER_GRANT_URI, DoorsKeys.LINKS_VERIFIED, DoorsKeys.LINKS_SELECTED,
    )

    @Test
    fun scanProducesSchemaConformingObservations() = runBlocking {
        assertEquals("doors", module.id)
        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue(reports > 0)
        assertFalse("own package must be excluded", obs.any { it.subject == context.packageName })

        val bySubject = obs.groupBy { it.subject }
        assertTrue("the Settings app is always installed", "com.android.settings" in bySubject)
        val settings = bySubject["com.android.settings"]!!.associate { it.key to it.value }
        assertEquals("true", settings[DoorsKeys.APP_SYSTEM])
        assertTrue("Settings exports activities", settings[DoorsKeys.EXPORTED_ACTIVITIES]!!.toInt() > 0)

        for ((subject, list) in bySubject) {
            val byKey = list.associate { it.key to it.value }
            assertEquals("duplicate keys for $subject", byKey.size, list.size)
            assertTrue(subject, DoorsKeys.APP_LABEL in byKey)
            assertTrue(subject, byKey[DoorsKeys.APP_SYSTEM] in setOf("true", "false"))
            for (k in countKeys) assertTrue("$subject $k=${byKey[k]}", (byKey[k]?.toIntOrNull() ?: -1) >= 0)
            val unprotected = byKey[DoorsKeys.EXPORTED_UNPROTECTED]!!.toInt()
            val exported = listOf(DoorsKeys.EXPORTED_ACTIVITIES, DoorsKeys.EXPORTED_SERVICES, DoorsKeys.EXPORTED_RECEIVERS, DoorsKeys.EXPORTED_PROVIDERS).sumOf { byKey[it]!!.toInt() }
            assertTrue("$subject unprotected <= exported", unprotected <= exported)
            byKey[DoorsKeys.HANDLER_SHARE]?.let { assertEquals(DoorsKeys.TRUE, it) }
            byKey[DoorsKeys.HANDLER_BROWSER]?.let { assertEquals(DoorsKeys.TRUE, it) }
        }
        assertTrue("some app offers a share target", obs.any { it.key == DoorsKeys.HANDLER_SHARE })

        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, obs, emptyList(), isFirstScan = true)) }
        assertTrue(drafts.none { it.sticky })
        for (d in drafts) {
            val actions = module.actionsFor(d)
            assertTrue(actions.any { it is FindingAction.OpenAppDetails })
            assertTrue(actions.any { it is FindingAction.Perform && it.label == "Open-by-default settings" })
            val system = bySubject[d.subject]!!.first { it.key == DoorsKeys.APP_SYSTEM }.value == "true"
            assertEquals(!system, actions.any { it is FindingAction.RequestUninstall })
        }
    }
}
