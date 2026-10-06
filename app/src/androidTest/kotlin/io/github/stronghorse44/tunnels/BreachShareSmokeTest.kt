package io.github.stronghorse44.tunnels

import android.content.ComponentName
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.breaches.BreachActivity
import io.github.stronghorse44.tunnels.breaches.BreachHolder
import io.github.stronghorse44.tunnels.breaches.BreachShare
import io.github.stronghorse44.tunnels.breaches.BreachShareProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * The breach hand-off on an emulator (the app's smoke job is the one CI job that runs instrumented tests for it),
 * without touching the network: the provider serves only the one address of the
 * held list, a use-limited number of times, from memory; the screen opens idle; the components are not exported.
 */
@RunWith(AndroidJUnit4::class)
class BreachShareSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = "fieldwork-breaches\t1\nexample\n".toByteArray()

    @Before
    @After
    fun clearHolder() = BreachHolder.shared.clear()

    private fun uri(token: String): Uri = BreachShare.uri(context, token)

    private fun read(uri: Uri): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(uri, "r")!!).use { it.readBytes() }

    private fun refused(uri: Uri, mode: String = "r") {
        try {
            context.contentResolver.openFileDescriptor(uri, mode)?.close()
            fail("opened $uri")
        } catch (_: FileNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    @Test
    fun providerRefusesAnUnknownToken() {
        refused(uri(BreachHolder.randomToken()))
        val token = BreachHolder.shared.put(data.copyOf(), "breaches-20261006.txt")
        refused(uri(BreachHolder.randomToken()))
        refused(uri("not-a-token"))
        refused(Uri.parse("content://${BreachShare.authority(context)}/catalogue/$token/extra"))
        refused(Uri.parse("content://${BreachShare.authority(context)}/other/$token"))
        refused(Uri.parse("content://${BreachShare.authority(context)}/catalogue/$token?x=1"))
        refused(uri(token), mode = "w")
        refused(uri(token), mode = "rw")
        // None of the refusals used up the list.
        assertArrayEquals(data, read(uri(token)))
    }

    @Test
    fun providerServesTheHeldBytesOncePerOpenAndThenStops() {
        val token = BreachHolder.shared.put(data.copyOf(), "breaches-20261006.txt")
        repeat(BreachHolder.MAX_SERVES) { assertArrayEquals(data, read(uri(token))) }
        refused(uri(token))
        assertNull(BreachHolder.shared.info())
    }

    @Test
    fun aNewFetchStopsTheOldAddress() {
        val old = BreachHolder.shared.put(data.copyOf(), "a.txt")
        val fresh = BreachHolder.shared.put("new".toByteArray(), "b.txt")
        refused(uri(old))
        assertArrayEquals("new".toByteArray(), read(uri(fresh)))
    }

    @Test
    fun providerAnswersTypeNameAndSize() {
        val token = BreachHolder.shared.put(data.copyOf(), "breaches-20261006.txt")
        assertEquals("application/vnd.fieldwork.breaches", context.contentResolver.getType(uri(token)))
        context.contentResolver.query(uri(token), null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("breaches-20261006.txt", c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(data.size.toLong(), c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        // Asking does not use up a serve.
        assertEquals(BreachHolder.MAX_SERVES, BreachHolder.shared.info()!!.servesLeft)
        assertNull(context.contentResolver.query(uri(BreachHolder.randomToken()), null, null, null, null))
    }

    @Test
    fun theComponentsAreNotExportedAndTheGrantIsPerAddress() {
        val pm = context.packageManager
        val provider = pm.getProviderInfo(ComponentName(context, BreachShareProvider::class.java), 0)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(BreachShare.authority(context), provider.authority)
        val activity = pm.getActivityInfo(ComponentName(context, BreachActivity::class.java), 0)
        assertFalse(activity.exported)
    }

    @Test
    fun theSendIntentGoesToLinxAloneWithAReadGrant() {
        val intent = BreachShare.sendIntent(uri(BreachHolder.randomToken()))
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("application/vnd.fieldwork.breaches", intent.type)
        assertEquals("io.github.stronghorse44.linx", intent.`package`)
        assertNull(intent.component)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, intent.clipData!!.itemCount)
    }

    @Test
    fun screenOpensIdle() {
        ActivityScenario.launch(BreachActivity::class.java).use { scenario ->
            Thread.sleep(1_000)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
