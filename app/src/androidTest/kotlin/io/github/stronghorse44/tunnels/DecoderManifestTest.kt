package io.github.stronghorse44.tunnels

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.decoder.DecoderClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins in the real app's merged manifest what the release gate does not record (audit M6): the sealed decoder is an
 * isolated, unexported service in its own `:decoder` process with no permission. Then binds it once, so the app's own
 * Application class starts in the sealed process (TunnelsApp returns early there) and the reader still reports sealed.
 */
@RunWith(AndroidJUnit4::class)
class DecoderManifestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSealedDecoderIsIsolatedUnexportedAndUnpermissioned() {
        val component = ComponentName(context.packageName, "io.github.stronghorse44.tunnels.decoder.IsolatedDecoderService")
        val info = context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertTrue("isolatedProcess", info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertFalse("exported", info.exported)
        assertEquals("${context.packageName}:decoder", info.processName)
        assertNull("permission", info.permission)
        assertFalse("this test runs in the app's own process, which is not isolated", Process.isIsolated())
    }

    @Test
    fun theRealAppsReaderStartsSealed() {
        val t = DecoderClient(context).selfTest()
        assertTrue("isolated: $t", t.isolated)
        assertTrue("no app data: $t", t.noAppData)
    }
}
