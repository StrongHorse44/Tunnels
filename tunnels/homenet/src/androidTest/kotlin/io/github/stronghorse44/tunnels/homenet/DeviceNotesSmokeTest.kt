package io.github.stronghorse44.tunnels.homenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.DeviceKeys
import io.github.stronghorse44.tunnels.lan.Watch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device notes on the emulator: names, presence counts and the watch list go through the encrypted store's settings
 * rows and back, a watched device's return is news at the next scan, and Forget clears the network's notes.
 */
@RunWith(AndroidJUnit4::class)
class DeviceNotesSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = "5a5a5a5a"
    private val notes get() = DeviceNotesStore(context)

    @After
    fun clear() = runBlocking { notes.forget(tag) }

    @Test
    fun notesRoundTripAndWatchSaysBack() = runBlocking {
        val laptop = DeviceKeys.Host(emptyList(), "192.168.1.50")
        val day = 20_372L
        assertEquals(DeviceNotesStore.ScanNotes.EMPTY, notes.afterScan(tag, listOf(laptop), day, emptyMap(), portsComplete = true))

        assertTrue(notes.setLabel(tag, laptop.key, "Lenovo laptop").startsWith("Saved"))
        assertTrue(notes.setWatched(tag, laptop.key, true, Watch.Now(listOf(445), "LENOVO-T14")).startsWith("Watching"))
        val view = notes.view(tag)
        assertNotNull(view)
        assertEquals("Lenovo laptop", view!!.labels[laptop.key])
        assertEquals(1, view.book.entries.getValue(laptop.key).scans)
        assertTrue(Watch.isWatched(view.watched, tag, laptop.key))

        // Gone at one scan, back at the next: news then, and the name shows on the scan.
        notes.afterScan(tag, emptyList(), day + 1, emptyMap(), portsComplete = true)
        val back = notes.afterScan(tag, listOf(laptop), day + 2, mapOf(laptop.key to Watch.Now(listOf(445), "LENOVO-T14")), portsComplete = true)
        assertEquals(Watch.Event.Back, back.events[laptop.key])
        assertEquals("Lenovo laptop", back.labels[laptop.key])
        assertEquals(2, notes.view(tag)!!.book.entries.getValue(laptop.key).days)

        assertTrue(notes.unwatch(laptop.key).startsWith("No longer watched"))
        assertFalse(Watch.isWatched(notes.view(tag)!!.watched, tag, laptop.key))
        notes.forget(tag)
        val cleared = notes.view(tag)!!
        assertTrue(cleared.labels.isEmpty() && cleared.book.entries.isEmpty())
    }
}
