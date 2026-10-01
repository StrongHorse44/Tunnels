package io.github.stronghorse44.tunnels.updater

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.installer.ApkInspector
import io.github.stronghorse44.tunnels.updates.UpdateCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.URL

/**
 * The updater on an emulator, without touching the network: the screen opens and stays idle until a tap, the
 * token round-trips through the encrypted store, the installed app's signers are readable for the comparison,
 * and the client's hop check refuses anything that is not GitHub over HTTPS.
 */
@RunWith(AndroidJUnit4::class)
class UpdaterSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun screenOpensIdle() {
        ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
            Thread.sleep(1_000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun tokenLivesInTheStoreAndCanBeForgotten() = runBlocking {
        UpdateToken.clear(context)
        assertNull(UpdateToken.read(context))
        UpdateToken.save(context, "github_pat_first_0123456789abcdef")
        UpdateToken.save(context, "github_pat_second_0123456789abcdef")
        // Only the latest is kept.
        assertEquals("github_pat_second_0123456789abcdef", UpdateToken.read(context))
        UpdateToken.clear(context)
        assertNull(UpdateToken.read(context))
    }

    @Test
    fun installedSignersAreReadableForTheComparison() {
        val installed = ApkInspector.installed(context, context.packageName)
        assertNotNull(installed)
        assertTrue("signing certificates readable", installed!!.signerSha256.isNotEmpty())
        // The installed app compared with itself passes everything but "newer".
        val same = UpdateCheck.verify(
            context.packageName, installed.versionCode, installed.signerSha256,
            context.packageName, installed.versionCode + 1, installed.signerSha256,
        )
        assertEquals(UpdateCheck.Verdict.Ok, same)
    }

    @Test
    fun onlyGitHubIsEverContacted() {
        for (url in listOf("http://api.github.com/x", "https://example.com/x", "https://github.com.evil.example/x")) {
            val e = runCatching { GitHubClient.requireAllowed(URL(url)) }.exceptionOrNull()
            assertTrue("$url: $e", e is IOException && e.message!!.contains("Refused to connect"))
        }
        GitHubClient.requireAllowed(URL("https://api.github.com/repos/StrongHorse44/Tunnels/releases"))
        GitHubClient.requireAllowed(URL("https://objects.githubusercontent.com/github-production-release-asset/1"))
    }
}
