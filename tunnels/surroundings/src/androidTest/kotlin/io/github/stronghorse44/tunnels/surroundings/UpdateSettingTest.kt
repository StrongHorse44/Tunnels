package io.github.stronghorse44.tunnels.surroundings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TunnelsStore.updateSetting` (core/store) against the real SQLCipher store. These live in this module's instrumented
 * tests because core/store has no instrumented test source set and no CI job runs one; this module's job does.
 */
@RunWith(AndroidJUnit4::class)
class UpdateSettingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun updateSettingIsAtomic() = runBlocking {
        val store = TunnelsStore.get(context)
        val key = "test.update_setting.atomic"
        store.putSetting(key, null)
        try {
            coroutineScope {
                (0 until 40).map { i -> async(Dispatchers.Default) { store.updateSetting(key) { (it ?: "") + "line$i\n" } } }.awaitAll()
            }
            val lines = store.setting(key)!!.trimEnd().lines()
            assertEquals("no append was lost", 40, lines.size)
            assertEquals(40, lines.toSet().size)
            assertTrue(lines.all { it.startsWith("line") })
        } finally {
            store.putSetting(key, null)
        }
    }

    @Test
    fun updateSettingNullDeletesTheRow() = runBlocking {
        val store = TunnelsStore.get(context)
        val key = "test.update_setting.delete"
        store.putSetting(key, "x")
        try {
            store.updateSetting(key) { stored ->
                assertEquals("x", stored)
                null
            }
            assertNull(store.setting(key))
            // Absent in, absent out: the transform sees null and a null result writes nothing.
            store.updateSetting(key) { stored ->
                assertNull(stored)
                null
            }
            assertNull(store.setting(key))
            // Returning what it was given leaves the row; a new value replaces it.
            store.updateSetting(key) { "a" }
            store.updateSetting(key) { it }
            assertEquals("a", store.setting(key))
            store.updateSetting(key) { it + "b" }
            assertEquals("ab", store.setting(key))
            // A transform that throws changes nothing.
            try {
                store.updateSetting(key) { error("no") }
                fail("the exception comes out")
            } catch (_: IllegalStateException) {
            }
            assertEquals("ab", store.setting(key))
        } finally {
            store.putSetting(key, null)
        }
    }
}
