package io.github.stronghorse44.tunnels.syspkg

import io.github.stronghorse44.tunnels.model.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SysPkgKeysAndStatsTest {
    private val t = SysPkgKeys.TUNNEL_ID

    @Test
    fun enabledValues() {
        assertEquals("enabled", SysPkgKeys.enabledValue(SysPkgKeys.STATE_DEFAULT))
        assertEquals("enabled", SysPkgKeys.enabledValue(SysPkgKeys.STATE_ENABLED))
        assertEquals("disabled", SysPkgKeys.enabledValue(SysPkgKeys.STATE_DISABLED))
        assertEquals("disabled-user", SysPkgKeys.enabledValue(SysPkgKeys.STATE_DISABLED_USER))
        assertEquals("disabled-until-used", SysPkgKeys.enabledValue(SysPkgKeys.STATE_DISABLED_UNTIL_USED))
        assertEquals("uninstalled-user", SysPkgKeys.enabledValue(SysPkgKeys.STATE_ENABLED, installedForUser = false))
        assertEquals("enabled", SysPkgKeys.enabledValue(99))
        assertFalse(SysPkgKeys.isDisabled("enabled"))
        assertFalse(SysPkgKeys.isDisabled(null))
        assertTrue(SysPkgKeys.isDisabled("disabled-user"))
        assertTrue(SysPkgKeys.isDisabled("uninstalled-user"))
        assertEquals("disabled by the user", SysPkgKeys.describeEnabled("disabled-user"))
        assertEquals("weird", SysPkgKeys.describeEnabled("weird"))
    }

    @Test
    fun privilegedPaths() {
        assertTrue(SysPkgKeys.isPrivilegedPath("/system/priv-app/Settings/Settings.apk"))
        assertTrue(SysPkgKeys.isPrivilegedPath("/system_ext/priv-app/SystemUI/SystemUI.apk"))
        assertTrue(SysPkgKeys.isPrivilegedPath("/product/priv-app/Foo/Foo.apk"))
        assertTrue(SysPkgKeys.isPrivilegedPath("/vendor/priv-app/Foo/Foo.apk"))
        assertTrue(SysPkgKeys.isPrivilegedPath("/apex/com.android.permission/priv-app/PermissionController@350000000/PermissionController.apk"))
        assertFalse(SysPkgKeys.isPrivilegedPath("/system/app/Egg/Egg.apk"))
        assertFalse(SysPkgKeys.isPrivilegedPath("/apex/com.android.wifi/app/WifiResources/WifiResources.apk"))
        assertFalse(SysPkgKeys.isPrivilegedPath("/data/app/~~abc==/com.example-1/base.apk"))
        assertFalse(SysPkgKeys.isPrivilegedPath("/data/priv-app/evil.apk"))
        assertFalse(SysPkgKeys.isPrivilegedPath(null))
        assertFalse(SysPkgKeys.isPrivilegedPath(""))
    }

    private fun pkg(name: String, label: String, enabled: String, known: Boolean, category: String, namespace: String, priv: Boolean, launcher: Boolean, updated: Boolean = false) = buildList {
        add(Observation(t, name, SysPkgKeys.LABEL, label))
        add(Observation(t, name, SysPkgKeys.ENABLED, enabled))
        add(Observation(t, name, SysPkgKeys.UPDATED, updated.toString()))
        add(Observation(t, name, SysPkgKeys.VERSION, "1 (1)"))
        add(Observation(t, name, SysPkgKeys.KNOWN, known.toString()))
        add(Observation(t, name, SysPkgKeys.CATEGORY, category))
        add(Observation(t, name, SysPkgKeys.NAMESPACE, namespace))
        add(Observation(t, name, SysPkgKeys.PRIVILEGED, priv.toString()))
        add(Observation(t, name, SysPkgKeys.HAS_LAUNCHER, launcher.toString()))
    }

    @Test
    fun statsFromObservations() {
        val obs = pkg("com.android.systemui", "System UI", "enabled", true, "ui", "aosp", true, false) +
            pkg("com.android.egg", "Android Easter Egg", "disabled-user", true, "other", "aosp", false, true) +
            pkg("com.vendor.b", "Vendor B", "disabled", false, "unknown", "other", true, false, updated = true) +
            pkg("com.vendor.a", "Vendor A", "enabled", false, "unknown", "other", false, true) +
            pkg("app.grapheneos.camera", "Camera", "enabled", true, "grapheneos", "grapheneos", false, true) +
            listOf(
                Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.TOTAL, "5"),
                Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.DISABLED, "2"),
                Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.UNKNOWN, "2"),
                Observation(t, SysPkgKeys.SUMMARY, SysPkgKeys.SKIPPED, "7"),
            )
        val s = SysPkgStats.from(obs)
        assertEquals(5, s.total)
        assertEquals(2, s.privileged)
        assertEquals(3, s.withLauncher)
        assertEquals(1, s.updated)
        assertEquals(7, s.skipped)
        assertEquals(listOf("aosp" to 2, "other" to 2, "grapheneos" to 1), s.byNamespace)
        assertEquals(listOf("unknown" to 2, "grapheneos" to 1, "other" to 1, "ui" to 1), s.byCategory)
        assertEquals(listOf("com.android.egg", "com.vendor.b"), s.disabled.map { it.packageName })
        assertEquals("disabled by the user", s.disabled[0].detail)
        assertEquals(listOf("com.vendor.a", "com.vendor.b"), s.unknown.map { it.packageName })
        assertEquals("other", s.unknown[0].detail)
        assertEquals(SysPkgStats.EMPTY, SysPkgStats.from(emptyList()))
        assertEquals(SysPkgStats.EMPTY, SysPkgStats.from(obs.filter { it.subject == SysPkgKeys.SUMMARY }))
    }
}
