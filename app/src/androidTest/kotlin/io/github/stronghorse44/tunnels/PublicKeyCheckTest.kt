package io.github.stronghorse44.tunnels

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.install.CheckAction
import io.github.stronghorse44.tunnels.install.CheckLevel
import io.github.stronghorse44.tunnels.install.PreInstallChecks
import io.github.stronghorse44.tunnels.install.PublicSigningKeys
import io.github.stronghorse44.tunnels.installer.ApkInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spec section 7 (the Installer's public-key warning, audit H1 follow-up): this debug APK is signed with Tunnels' retired
 * debug key, committed in `app/debug.keystore` and pinned in `gate/baseline.json`. Read through the Installer's own
 * inspector, it is named as a publicly known key; and since the installed copy (this app) holds the same key, the
 * warning offers to uninstall it, while the install stays possible (I-Q1).
 */
@RunWith(AndroidJUnit4::class)
class PublicKeyCheckTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thisDebugBuildIsSignedWithTheRetiredPublicKey() {
        val apk = File(context.applicationInfo.sourceDir)
        val info = ApkInspector.inspect(context, apk, listOf(apk))
        assertEquals(setOf("0F23F08BBE02F1D0FF9B4A721AD523C2C977A9178A2B842C38EDE005F3DE34C2"), info.facts.signerSha256)
        assertEquals("Tunnels' retired debug key", PublicSigningKeys.match(info.facts.signerSha256.single())?.name)

        val installed = ApkInspector.installed(context, context.packageName)
        assertNotNull(installed)
        val report = PreInstallChecks.evaluate(info.facts, installed, ApkInspector.device(context))
        val first = report.checks.first()
        assertEquals(CheckLevel.WARN, first.level)
        assertEquals(PreInstallChecks.PUBLIC_KEY_TITLE, first.title)
        assertEquals(listOf(CheckAction.DONT_INSTALL, CheckAction.UNINSTALL_INSTALLED), first.actions)
        assertNotNull(first.why)
        assertFalse("a warning, not a blocker", report.blocked)
    }
}
