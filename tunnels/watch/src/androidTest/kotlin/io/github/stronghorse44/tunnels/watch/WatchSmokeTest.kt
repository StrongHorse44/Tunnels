package io.github.stronghorse44.tunnels.watch

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs a real check on the emulator (no tunnels are registered in this test APK) and drives the scheduler. */
@RunWith(AndroidJUnit4::class)
class WatchSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aCheckRecordsItsStatusAndReadsPackageChanges() = runBlocking {
        val store = TunnelsRuntime.get(context).store
        store.putSetting(WatchStatus.KEY, null)
        val outcome = WatchRunner.run(context, notify = false)
        assertEquals("first check", outcome.plan.reason)
        val status = WatchStatus.decode(store.setting(WatchStatus.KEY))
        assertFalse(status.neverRan)
        assertTrue("boot count is readable", status.bootCount >= 0)
        assertTrue("package sequence is readable", status.packageSequence >= 0)

        // Straight after, nothing about apps changed: a quick check.
        val second = WatchRunner.run(context, notify = false)
        assertEquals("quick check", second.plan.reason)
        assertFalse(second.plan.appFiles)
    }

    @Test
    fun theSchedulerArmsAndCancelsTheJob() {
        WatchScheduler.apply(context, WatchSettings(enabled = true, intervalHours = 6))
        val job = WatchScheduler.pending(context)
        assertNotNull(job)
        assertTrue(job!!.isPeriodic)
        assertTrue(job.isPersisted)
        assertEquals(6 * 3_600_000L, job.intervalMillis)
        WatchScheduler.apply(context, WatchSettings(enabled = false))
        assertNull(WatchScheduler.pending(context))
    }
}
