package io.github.stronghorse44.tunnels

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun homeLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun permissionPolicyAndRuntimeOpens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList().orEmpty()
        val runtime = TunnelsRuntime.get(context)
        val modules = runtime.registry.modules.keys

        // Rule #1: INTERNET is approved for the Traffic and Home network session modules only, so it may
        // appear in the merged manifest only when one of them is in the build. (The Gradle verifyPermissions
        // task and the aapt2 audit check which module declared it; this checks the shipped APK's shape.)
        if ("android.permission.INTERNET" in perms) {
            assertTrue(
                "INTERNET is declared but neither traffic nor home_network is registered",
                "traffic" in modules || "home_network" in modules,
            )
        }

        // Rule #6: a fresh install holds no runtime permission; each tunnel asks when it is opened.
        val grantedRuntime = perms.filter { p ->
            val dangerous = runCatching { pm.getPermissionInfo(p, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }
                .getOrDefault(false)
            dangerous && context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }
        assertEquals("runtime permissions granted on a fresh install", emptyList<String>(), grantedRuntime)

        assertTrue("key level reported", TunnelsStore.keySecurityLevel().isNotBlank())
        assertTrue(modules.isNotEmpty() && modules.all { it.isNotBlank() })
    }
}
