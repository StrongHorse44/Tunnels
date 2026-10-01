package io.github.stronghorse44.tunnels

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun noInternetPermissionAndRuntimeOpens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toList().orEmpty()
        assertFalse("INTERNET must not be requested by the app", "android.permission.INTERNET" in perms)
        val runtime = TunnelsRuntime.get(context)
        assertTrue("key level reported", runtime.store.let { io.github.stronghorse44.tunnels.store.TunnelsStore.keySecurityLevel() }.isNotBlank())
        assertTrue(runtime.registry.modules.keys.all { it.isNotBlank() })
    }
}
