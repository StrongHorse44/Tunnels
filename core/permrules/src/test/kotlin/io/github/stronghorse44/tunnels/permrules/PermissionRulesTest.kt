package io.github.stronghorse44.tunnels.permrules

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRulesTest {
    private val t = PermissionKeys.TUNNEL_ID

    private fun app(
        pkg: String,
        label: String = pkg,
        system: Boolean = false,
        granted: List<String> = emptyList(),
        denied: List<String> = emptyList(),
        network: String = PermissionKeys.NA,
        accessibility: String? = null,
    ): List<Observation> = buildList {
        add(Observation(t, pkg, PermissionKeys.APP_LABEL, label))
        add(Observation(t, pkg, PermissionKeys.APP_SYSTEM, system.toString()))
        add(Observation(t, pkg, PermissionKeys.TOGGLE_NETWORK, network))
        granted.forEach { add(Observation(t, pkg, PermissionKeys.permKey(it), PermissionKeys.GRANTED)) }
        denied.forEach { add(Observation(t, pkg, PermissionKeys.permKey(it), PermissionKeys.DENIED)) }
        accessibility?.let { add(Observation(t, pkg, PermissionKeys.ACCESS_ACCESSIBILITY, it)) }
    }

    private fun first(current: List<Observation>) = RuleContext(t, current, emptyList(), isFirstScan = true)

    private fun next(before: List<Observation>, after: List<Observation>) = RuleContext(t, after, DiffEngine.diff(before, after), isFirstScan = false)

    private fun drafts(ctx: RuleContext, kind: String): List<FindingDraft> = PermissionRules.all.flatMap { it.evaluate(ctx) }.filter { it.kind == kind }

    private val camera = "android.permission.CAMERA"
    private val mic = "android.permission.RECORD_AUDIO"
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val background = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private val sms = "android.permission.READ_SMS"
    private val notify = "android.permission.POST_NOTIFICATIONS"
    private val overlay = "android.permission.SYSTEM_ALERT_WINDOW"

    @Test
    fun catalogClassifiesKnownAndUnknownNames() {
        assertEquals(PermissionGroup.CAMERA, PermissionCatalog.groupOf(camera))
        assertEquals(PermissionGroup.SMS, PermissionCatalog.groupOf("android.permission.RECEIVE_MMS"))
        assertEquals(PermissionGroup.BODY_SENSORS, PermissionCatalog.groupOf("android.permission.health.READ_HEART_RATE"))
        assertEquals(PermissionGroup.NETWORK, PermissionCatalog.groupOf(PermissionCatalog.INTERNET))
        assertEquals(PermissionGroup.OTHER, PermissionCatalog.groupOf("com.example.CUSTOM"))
        assertEquals(PermissionGroup.OTHER, PermissionCatalog.groupOf(PermissionCatalog.OTHER_SENSORS))
        assertFalse(PermissionGroup.NOTIFICATIONS.isSensitive)
        assertTrue(PermissionGroup.ACCESSIBILITY.isSensitive)
        assertEquals(PermissionCatalog.sensitive.first().weight, 3)
    }

    @Test
    fun describeOrdersMostIntrusiveFirst() {
        assertEquals("background location, camera, approximate location", PermissionCatalog.describe(listOf(PermissionGroup.LOCATION_COARSE, PermissionGroup.CAMERA, PermissionGroup.LOCATION_BACKGROUND)))
        assertEquals("camera, POST_NOTIFICATIONS", PermissionCatalog.describePermissions(listOf(notify, camera)))
        val many = (1..9).map { "com.example.P$it" }
        assertEquals("P1, P2, P3, P4, P5, P6 and 3 more", PermissionCatalog.describePermissions(many))
    }

    @Test
    fun dangerousGrantedListsOnlySensitiveGroups() {
        val ctx = first(
            app("com.a", granted = listOf(camera, mic, notify), denied = listOf(fine)) +
                app("com.b", granted = listOf(notify)) +
                app("com.sys", system = true, granted = listOf(camera, mic)) +
                app("com.sysloc", system = true, granted = listOf(background, camera)),
        )
        val d = drafts(ctx, PermissionRules.DANGEROUS_GRANTED).associateBy { it.subject }
        assertEquals(setOf("com.a", "com.sysloc"), d.keys)
        assertEquals("Granted: camera, microphone", d["com.a"]!!.evidence)
        assertEquals("Granted: background location, camera", d["com.sysloc"]!!.evidence)
        assertEquals(Severity.NOTICE, d["com.a"]!!.severity)
        assertFalse(d["com.a"]!!.sticky)
    }

    @Test
    fun riskyComboNamesEachCombination() {
        val ctx = first(
            app("com.tracker", granted = listOf(background, fine), network = PermissionKeys.ON) +
                app("com.offline", granted = listOf(background, sms), network = PermissionKeys.OFF) +
                app("com.a11y", granted = listOf(overlay), network = PermissionKeys.ON, accessibility = PermissionKeys.ENABLED) +
                app("com.installed", granted = listOf(overlay), network = PermissionKeys.ON, accessibility = PermissionKeys.INSTALLED) +
                app("com.sys", system = true, granted = listOf(sms), network = PermissionKeys.ON),
        )
        val d = drafts(ctx, PermissionRules.RISKY_COMBO).associateBy { it.subject }
        assertEquals(setOf("com.tracker", "com.a11y"), d.keys)
        assertEquals("Background location + network on", d["com.tracker"]!!.evidence)
        assertEquals("Accessibility service + network on; Draw over other apps + accessibility service", d["com.a11y"]!!.evidence)
        assertEquals(Severity.WARN, d["com.tracker"]!!.severity)
    }

    @Test
    fun changeRulesNeverFireOnFirstScan() {
        val ctx = first(app("com.a", granted = listOf(camera), network = PermissionKeys.ON))
        val kinds = PermissionRules.all.flatMap { it.evaluate(ctx) }.map { it.kind }.toSet()
        assertEquals(setOf(PermissionRules.DANGEROUS_GRANTED), kinds)
    }

    @Test
    fun permissionGainedGroupsPerAppAndSkipsNewApps() {
        val before = app("com.a", granted = listOf(notify), denied = listOf(camera, mic))
        val after = app("com.a", granted = listOf(notify, camera, mic, fine)) + app("com.new", granted = listOf(camera))
        val gained = drafts(next(before, after), PermissionRules.PERMISSION_GAINED)
        assertEquals(1, gained.size)
        assertEquals("com.a", gained[0].subject)
        assertEquals("Newly granted: camera, microphone, precise location", gained[0].evidence)
        assertEquals(Severity.WARN, gained[0].severity)
        assertTrue(gained[0].sticky)
    }

    @Test
    fun permissionLostCoversRevokedAndDroppedButNotUninstalled() {
        val before = app("com.a", granted = listOf(camera, mic, notify)) + app("com.gone", granted = listOf(sms))
        val after = app("com.a", granted = listOf(notify), denied = listOf(camera))
        val lost = drafts(next(before, after), PermissionRules.PERMISSION_LOST)
        assertEquals(1, lost.size)
        assertEquals("com.a", lost[0].subject)
        assertEquals("No longer granted: camera, microphone", lost[0].evidence)
        assertEquals(Severity.INFO, lost[0].severity)
        assertTrue(lost[0].sticky)
    }

    @Test
    fun networkEnabledOnlyForOffOrNaToOn() {
        val before = app("com.a", network = PermissionKeys.OFF) + app("com.b", network = PermissionKeys.NA) + app("com.c", network = PermissionKeys.ON)
        val after = app("com.a", network = PermissionKeys.ON) + app("com.b", network = PermissionKeys.ON) + app("com.c", network = PermissionKeys.OFF)
        val d = drafts(next(before, after), PermissionRules.NETWORK_ENABLED).associateBy { it.subject }
        assertEquals(setOf("com.a", "com.b"), d.keys)
        assertEquals("Network toggle was off, now on", d["com.a"]!!.evidence)
        assertEquals("Network toggle was not requested, now on", d["com.b"]!!.evidence)
        assertTrue(d.values.all { it.sticky && it.severity == Severity.WARN })
    }

    @Test
    fun newAppNamesWhatItHolds() {
        val before = app("com.a")
        val after = app("com.a") + app("com.new", label = "Shiny", granted = listOf(camera, sms), network = PermissionKeys.ON) + app("com.quiet", label = "Quiet")
        val d = drafts(next(before, after), PermissionRules.NEW_APP).associateBy { it.subject }
        assertEquals(setOf("com.new", "com.quiet"), d.keys)
        assertEquals("New app \"Shiny\" with granted: SMS, camera, network on", d["com.new"]!!.evidence)
        assertEquals("New app \"Quiet\" with no sensitive permissions granted", d["com.quiet"]!!.evidence)
        assertTrue(d["com.new"]!!.sticky)
        assertEquals(Severity.NOTICE, d["com.new"]!!.severity)
    }

    @Test
    fun summaryCountsGroupsAndToggles() {
        val obs = app("com.a", granted = listOf(camera, mic), network = PermissionKeys.ON) +
            app("com.b", granted = listOf(camera), network = PermissionKeys.OFF) +
            app("com.sys", system = true, network = PermissionKeys.NA) +
            listOf(
                Observation(t, "com.a", PermissionKeys.TOGGLE_SENSORS, PermissionKeys.OFF),
                Observation(t, "com.b", PermissionKeys.TOGGLE_SENSORS, PermissionKeys.ON),
            )
        val s = PermissionSummary.of(obs)
        assertEquals(3, s.apps)
        assertEquals(2, s.userApps)
        assertEquals(listOf(PermissionGroup.CAMERA to 2, PermissionGroup.MICROPHONE to 1), s.perGroup)
        assertEquals(1, s.networkOn)
        assertEquals(1, s.networkOff)
        assertEquals(1, s.networkNotRequested)
        assertEquals(1, s.sensorsOff)
        assertTrue(s.sensorsVisible)
        assertFalse(PermissionSummary.of(app("com.x")).sensorsVisible)
        assertEquals(PermissionSummary.EMPTY, PermissionSummary.of(emptyList()))
    }

    @Test
    fun appStateReadsBackObservations() {
        val s = AppPermissionState.of("com.a", app("com.a", label = "A", granted = listOf(camera, notify), denied = listOf(mic), network = PermissionKeys.ON, accessibility = PermissionKeys.ENABLED))
        assertEquals("A", s.label)
        assertTrue(s.networkOn)
        assertEquals(setOf(PermissionGroup.CAMERA, PermissionGroup.ACCESSIBILITY), s.grantedGroups)
        assertEquals(listOf(camera, notify), s.grantedPermissions)
        assertNull(AppPermissionState.of("com.z", emptyList()).label)
        assertEquals("com.z", AppPermissionState.of("com.z", emptyList()).displayName)
    }
}
